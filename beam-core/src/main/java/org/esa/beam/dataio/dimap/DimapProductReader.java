/*
 * $Id: DimapProductReader.java,v 1.4 2007/03/19 15:52:27 marcop Exp $
 *
 * Copyright (C) 2002 by Brockmann Consult (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.esa.beam.dataio.dimap;

import com.bc.ceres.core.Assert;
import com.bc.ceres.core.ProgressMonitor;

import org.esa.beam.dataio.propertystore.PropertyDataStore;
import org.esa.beam.framework.dataio.AbstractProductReader;
import org.esa.beam.framework.dataio.DecodeQualification;
import org.esa.beam.framework.dataio.ProductReaderPlugIn;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FilterBand;
import org.esa.beam.framework.datamodel.GeoCoding;
import org.esa.beam.framework.datamodel.PixelGeoCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.TiePointGrid;
import org.esa.beam.framework.datamodel.VectorDataNode;
import org.esa.beam.framework.datamodel.VirtualBand;
import org.esa.beam.jai.ImageManager;
import org.esa.beam.util.Debug;
import org.esa.beam.util.io.FileUtils;
import org.esa.beam.util.logging.BeamLogManager;
import org.geotools.data.DataStore;
import org.geotools.data.FeatureSource;
import org.geotools.data.crs.ForceCoordinateSystemFeatureResults;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.SchemaException;
import org.jdom.Document;
import org.jdom.input.DOMBuilder;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Hashtable;
import java.util.Map;

import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The <code>DimapProductReader</code> class is an implementation of the <code>ProductReader</code> interface
 * exclusively for data products having the BEAM-DIMAP product format.
 *
 * @author Sabine Embacher
 * @author Norman Fomferra
 * @version $Revision$ $Date$
 * @see org.esa.beam.dataio.dimap.DimapProductReaderPlugIn
 */
public class DimapProductReader extends AbstractProductReader {

    private Product product;

    private File inputDir;
    private File inputFile;
    private Map<Band, ImageInputStream> bandInputStreams;

    private int sourceRasterWidth;
    private int sourceRasterHeight;
    private Map<Band, File> bandDataFiles;

    /**
     * Construct a new instance of a product reader for the given BEAM-DIMAP product reader plug-in.
     *
     * @param readerPlugIn the given BEAM-DIMAP product writer plug-in, must not be <code>null</code>
     */
    public DimapProductReader(ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    public Product getProduct() {
        return product;
    }

    public File getInputDir() {
        return inputDir;
    }

    public File getInputFile() {
        return inputFile;
    }

    public int getSourceRasterWidth() {
        return sourceRasterWidth;
    }

    public int getSourceRasterHeight() {
        return sourceRasterHeight;
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface method. Clients implementing this
     * method can be sure that the input object and eventually the subset information has already been set.
     * <p/>
     * <p>This method is called as a last step in the <code>readProductNodes(input, subsetInfo)</code> method.
     *
     * @throws java.io.IOException if an I/O error occurs
     * @throws org.esa.beam.framework.dataio.IllegalFileFormatException
     *                             if the input file in not decodeable
     */
    @Override
    protected Product readProductNodesImpl() throws IOException {
        return processProduct(null);
    }

    // todo - Put this into interface ReconfigurableProductReader and make DimapProductReader implement it
    public void bindProduct(Object input, Product product) throws IOException {
        Assert.notNull(input, "input");
        Assert.notNull(product, "product");
        setInput(input);
        processProduct(product);
    }

    protected Product processProduct(Product product) throws IOException {
        initInput();
        Document dom = readDom();

        this.product = product == null ? DimapProductHelpers.createProduct(dom) : product;
        this.product.setProductReader(this);

        if (product == null) {
            readTiePointGrids(dom);
        }

        sourceRasterWidth = this.product.getSceneRasterWidth();
        sourceRasterHeight = this.product.getSceneRasterHeight();

        bindBandsToFiles(dom);
        if (product == null) {
            initGeoCodings(dom);
            readVectorData();
        }
        this.product.setProductReader(this);
        this.product.setFileLocation(inputFile);
        this.product.setModified(false);
        return this.product;
    }


    private void initGeoCodings(Document dom) {
        final GeoCoding[] geoCodings = DimapProductHelpers.createGeoCoding(dom, product);
        if (geoCodings != null) {
            if (geoCodings.length == 1) {
                product.setGeoCoding(geoCodings[0]);
            } else {
                for (int i = 0; i < geoCodings.length; i++) {
                    product.getBandAt(i).setGeoCoding(geoCodings[i]);
                }
            }
        } else {
            final Band lonBand = product.getBand("longitude");
            final Band latBand = product.getBand("latitude");
            if (latBand != null && lonBand != null) {
                product.setGeoCoding(new PixelGeoCoding(latBand, lonBand, null, 6));
            }
        }
    }

    private void bindBandsToFiles(Document dom) {
        bandDataFiles = DimapProductHelpers.getBandDataFiles(dom, product, getInputDir());
        final Band[] bands = product.getBands();
        for (final Band band : bands) {
            if (band instanceof VirtualBand || band instanceof FilterBand) {
                continue;
            }
            final File dataFile = bandDataFiles.get(band);
            if (dataFile == null || !dataFile.canRead()) {
                product.removeBand(band);
                BeamLogManager.getSystemLogger().warning(
                        "DimapProductReader: Unable to read file '" + dataFile + "' referenced by '" + band.getName() + "'.");
                BeamLogManager.getSystemLogger().warning(
                        "DimapProductReader: Removed band '" + band.getName() + "' from product '" + product.getFileLocation() + "'.");
            }
        }
    }

    private void readTiePointGrids(Document jDomDocument) throws IOException {
        final String[] tiePointGridNames = product.getTiePointGridNames();
        for (String tiePointGridName : tiePointGridNames) {
            final TiePointGrid tiePointGrid = product.getTiePointGrid(tiePointGridName);
            String dataFile = DimapProductHelpers.getTiePointDataFile(jDomDocument, tiePointGrid.getName());
            dataFile = FileUtils.exchangeExtension(dataFile, DimapProductConstants.IMAGE_FILE_EXTENSION);
            FileImageInputStream inputStream = null;
            try {
                inputStream = new FileImageInputStream(new File(inputDir, dataFile));
                final float[] floats = ((float[]) tiePointGrid.getData().getElems());
                inputStream.seek(0);
                inputStream.readFully(floats, 0, floats.length);
                inputStream.close();
                inputStream = null;
                // See if we have a -180...+180 or a 0...360 degree discontinuity
                if (tiePointGrid.getDiscontinuity() != TiePointGrid.DISCONT_NONE) {
                    tiePointGrid.setDiscontinuity(TiePointGrid.getDiscontinuity(floats));
                }
            } catch (IOException e) {
                throw new IOException(MessageFormat.format("I/O error while reading tie-point grid ''{0}''.", tiePointGridName), e);
            } finally {
                if (inputStream != null) {
                    inputStream.close();
                }
            }
        }
    }

    private Document readDom() throws IOException {
        Document dom;
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            final DocumentBuilder builder = factory.newDocumentBuilder();
            Debug.trace("DimapProductReader: about to open file '" + inputFile + "'..."); /*I18N*/
            final InputStream is = new BufferedInputStream(new FileInputStream(inputFile), 256 * 1024);
            dom = new DOMBuilder().build(builder.parse(is));
            is.close();
        } catch (Exception e) {
            throw new IOException("Failed to read DIMAP XML header.", e);
        }
        return dom;
    }

    private void initInput() throws IOException {
        if (getInput() instanceof String) {
            inputFile = new File((String) getInput());
        } else if (getInput() instanceof File) {
            inputFile = (File) getInput();
        } else {
            throw new IllegalArgumentException("unsupported input source: " + getInput());  /*I18N*/
        }
        if (DecodeQualification.UNABLE.equals(getReaderPlugIn().getDecodeQualification(inputFile))) {
            throw new IOException("Not a '" + DimapProductConstants.DIMAP_FORMAT_NAME + "' product."); /*I18N*/
        }

        Debug.assertNotNull(inputFile); // super.readProductNodes should have checked getInput() != null already
        inputDir = inputFile.getParentFile();
        if (inputDir == null) {
            inputDir = new File(".");
        }
    }

    /**
     * The template method which is called by the {@link org.esa.beam.framework.dataio.AbstractProductReader#readBandRasterDataImpl(int, int, int, int, int, int, org.esa.beam.framework.datamodel.Band, int, int, int, int, org.esa.beam.framework.datamodel.ProductData, com.bc.ceres.core.ProgressMonitor)} }
     * method after an optional spatial subset has been applied to the input parameters.
     * <p/>
     * <p>The destination band, buffer and region parameters are exactly the ones passed to the original {@link
     * org.esa.beam.framework.dataio.AbstractProductReader#readBandRasterDataImpl} call. Since the
     * <code>destOffsetX</code> and <code>destOffsetY</code> parameters are already taken into acount in the
     * <code>sourceOffsetX</code> and <code>sourceOffsetY</code> parameters, an implementor of this method is free to
     * ignore them.
     *
     * @param sourceOffsetX the absolute X-offset in source raster co-ordinates
     * @param sourceOffsetY the absolute Y-offset in source raster co-ordinates
     * @param sourceWidth   the width of region providing samples to be read given in source raster co-ordinates
     * @param sourceHeight  the height of region providing samples to be read given in source raster co-ordinates
     * @param sourceStepX   the sub-sampling in X direction within the region providing samples to be read
     * @param sourceStepY   the sub-sampling in Y direction within the region providing samples to be read
     * @param destBand      the destination band which identifies the data source from which to read the sample values
     * @param destBuffer    the destination buffer which receives the sample values to be read
     * @param destOffsetX   the X-offset in the band's raster co-ordinates
     * @param destOffsetY   the Y-offset in the band's raster co-ordinates
     * @param destWidth     the width of region to be read given in the band's raster co-ordinates
     * @param destHeight    the height of region to be read given in the band's raster co-ordinates
     * @param pm            a monitor to inform the user about progress
     *
     * @throws java.io.IOException if  an I/O error occurs
     * @see #getSubsetDef
     */
    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY,
                                          int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY,
                                          Band destBand,
                                          int destOffsetX, int destOffsetY,
                                          int destWidth, int destHeight,
                                          ProductData destBuffer,
                                          ProgressMonitor pm) throws IOException {
        final int sourceMinX = sourceOffsetX;
        final int sourceMinY = sourceOffsetY;
        final int sourceMaxX = sourceOffsetX + sourceWidth - 1;
        final int sourceMaxY = sourceOffsetY + sourceHeight - 1;

        final File dataFile = bandDataFiles.get(destBand);
        final ImageInputStream inputStream = getOrCreateImageInputStream(destBand, dataFile);

        int destPos = 0;

        pm.beginTask("Reading band '" + destBand.getName() + "'...", sourceMaxY - sourceMinY);
        // For each scan in the data source
        try {
            synchronized (inputStream) {
            for (int sourceY = sourceMinY; sourceY <= sourceMaxY; sourceY += sourceStepY) {
                if (pm.isCanceled()) {
                    break;
                }
                final int sourcePosY = sourceY * sourceRasterWidth;
                    if (sourceStepX == 1) {
                        destBuffer.readFrom(destPos, destWidth, inputStream, sourcePosY + sourceMinX);
                        destPos += destWidth;
                    } else {
                        for (int sourceX = sourceMinX; sourceX <= sourceMaxX; sourceX += sourceStepX) {
                            destBuffer.readFrom(destPos, 1, inputStream, sourcePosY + sourceX);
                            destPos++;
                        }
                    }
                }
                pm.worked(1);
            }
        } finally {
            pm.done();
        }
    }

    /**
     * Closes the access to all currently opened resources such as file input streams and all resources of this children
     * directly owned by this reader. Its primary use is to allow the garbage collector to perform a vanilla job.
     * <p/>
     * <p>This method should be called only if it is for sure that this object instance will never be used again. The
     * results of referencing an instance of this class after a call to <code>close()</code> are undefined.
     * <p/>
     * <p>Overrides of this method should always call <code>super.close();</code> after disposing this instance.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void close() throws IOException {
        if (bandInputStreams == null) {
            return;
        }
        for (ImageInputStream imageInputStream : bandInputStreams.values()) {
            (imageInputStream).close();
        }
        bandInputStreams.clear();
        bandInputStreams = null;
        super.close();
    }

    private ImageInputStream getOrCreateImageInputStream(Band band, File file) throws IOException {
        ImageInputStream inputStream = getImageInputStream(band);
        if (inputStream == null) {
            inputStream = new FileImageInputStream(file);
            if (bandInputStreams == null) {
                bandInputStreams = new Hashtable<Band, ImageInputStream>();
            }
            bandInputStreams.put(band, inputStream);
        }
        return inputStream;
    }

    private ImageInputStream getImageInputStream(Band band) {
        if (bandInputStreams != null) {
            return bandInputStreams.get(band);
        }
        return null;
    }
    
    private void readVectorData() {
        File dataDir = new File(inputDir, FileUtils.getFilenameWithoutExtension(inputFile) + DimapProductConstants.DIMAP_DATA_DIRECTORY_EXTENSION);
        File vectorDataDir = new File(dataDir, "vector_data");
        if (vectorDataDir.exists()) {
            File[] vectorFiles = vectorDataDir.listFiles();
            for (File vectorFile : vectorFiles) {
                String propertiesSuffix = ".properties";
                String name = vectorFile.getName();
                if (name.endsWith(propertiesSuffix)) {
                    name = name.substring(0, name.length() - propertiesSuffix.length());
                }
                FeatureSource<SimpleFeatureType, SimpleFeature> featureSource;
                try {
                    DataStore dataStore = new PropertyDataStore(vectorDataDir, name);
                    featureSource = dataStore.getFeatureSource(name);
                    FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection = featureSource.getFeatures();
                    CoordinateReferenceSystem modelCrs = ImageManager.getModelCrs(product.getGeoCoding());
                    if (modelCrs != null) {
                        featureCollection = new ForceCoordinateSystemFeatureResults(featureCollection, modelCrs);
                    }
                    DefaultFeatureCollection defaultFeatureCollection = new DefaultFeatureCollection(featureCollection);
                    VectorDataNode vectorDataNode = new VectorDataNode(name, defaultFeatureCollection);
                    product.getVectorDataGroup().add(vectorDataNode);
                } catch (IOException e) {
                    BeamLogManager.getSystemLogger().throwing("DimapProductReader", "readVectorData", e);
                } catch (SchemaException e) {
                    BeamLogManager.getSystemLogger().throwing("DimapProductReader", "readVectorData", e);
                }
            }
        }
    }
}
