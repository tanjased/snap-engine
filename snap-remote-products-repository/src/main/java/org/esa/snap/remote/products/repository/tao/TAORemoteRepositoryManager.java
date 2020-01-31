package org.esa.snap.remote.products.repository.tao;

import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.auth.Credentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.esa.snap.remote.products.repository.*;
import org.esa.snap.remote.products.repository.listener.ProductListDownloaderListener;
import org.esa.snap.remote.products.repository.listener.ProgressListener;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import ro.cs.tao.configuration.ConfigurationManager;
import ro.cs.tao.datasource.*;
import ro.cs.tao.datasource.param.CommonParameterNames;
import ro.cs.tao.datasource.param.DataSourceParameter;
import ro.cs.tao.datasource.param.ParameterName;
import ro.cs.tao.datasource.param.QueryParameter;
import ro.cs.tao.datasource.remote.FetchMode;
import ro.cs.tao.eodata.EOProduct;
import ro.cs.tao.eodata.enums.ProductStatus;
import ro.cs.tao.utils.HttpMethod;
import ro.cs.tao.utils.NetUtils;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.lang.InterruptedException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by jcoravu on 21/11/2019.
 */
public class TAORemoteRepositoryManager {

    private static final Logger logger = Logger.getLogger(TAORemoteRepositoryManager.class.getName());

    private static final TAORemoteRepositoryManager instance = new TAORemoteRepositoryManager();

    private final RemoteProductsRepositoryProvider[] remoteRepositoryProductProviders;
    private final Map<String, DataSourceComponent> downloadingProducts;

    private TAORemoteRepositoryManager() {
        ConfigurationManager.setConfigurationProvider(new TAOConfigurationProvider());
        Set<DataSource> services = DataSourceManager.getInstance().getRegisteredDataSources();
        this.remoteRepositoryProductProviders = new RemoteProductsRepositoryProvider[services.size()];
        int index = 0;
        for (DataSource dataSource : services) {
            this.remoteRepositoryProductProviders[index++] = new TAORemoteRepositoryProvider(dataSource.getId());
        }

        if (this.remoteRepositoryProductProviders.length > 1) {
            // sort alphabetically by repository name
            Comparator<RemoteProductsRepositoryProvider> comparator = new Comparator<RemoteProductsRepositoryProvider>() {
                @Override
                public int compare(RemoteProductsRepositoryProvider leftProvider, RemoteProductsRepositoryProvider rightProvider) {
                    return leftProvider.getRepositoryName().compareToIgnoreCase(rightProvider.getRepositoryName());
                }
            };
            for (int i=0; i<this.remoteRepositoryProductProviders.length-1; i++) {
                for (int j=i+1; j<this.remoteRepositoryProductProviders.length; j++) {
                    int result = comparator.compare(this.remoteRepositoryProductProviders[i], this.remoteRepositoryProductProviders[j]);
                    if (result > 0) {
                        RemoteProductsRepositoryProvider aux = this.remoteRepositoryProductProviders[i];
                        this.remoteRepositoryProductProviders[i] = this.remoteRepositoryProductProviders[j];
                        this.remoteRepositoryProductProviders[j] = aux;
                    }
                }
            }
        }

        this.downloadingProducts = new HashMap<>();
    }

    public static TAORemoteRepositoryManager getInstance() {
        return instance;
    }

    public RemoteProductsRepositoryProvider[] getRemoteProductsRepositoryProviders() {
        return this.remoteRepositoryProductProviders;
    }

    public void cancelDownloadProduct(String dataSourceName, RepositoryProduct repositoryProduct) {
        String key = buildKey(dataSourceName, repositoryProduct);
        DataSourceComponent downloadStrategy;
        synchronized (this.downloadingProducts) {
            downloadStrategy = this.downloadingProducts.remove(key);
        }
        if (downloadStrategy != null) {
            downloadStrategy.cancel();
        }
    }

    public Path downloadProduct(String dataSourceName, RepositoryProduct repositoryProduct, Credentials credentials, Path targetFolderPath, ProgressListener progressListener) throws Exception {
        String key = buildKey(dataSourceName, repositoryProduct);
        DataSourceComponent dataSourceComponent = null;
        try {
            synchronized (this.downloadingProducts) {
                dataSourceComponent = this.downloadingProducts.get(repositoryProduct);
                if (dataSourceComponent == null) {
                    dataSourceComponent = new DataSourceComponent();
                    this.downloadingProducts.put(key, dataSourceComponent);
                } else {
                    throw new IllegalArgumentException("The product '" + repositoryProduct.getName()+"' is already downloading.");
                }
            }
            final List<String> downloadMessages = new ArrayList<>();
            dataSourceComponent = new DataSourceComponent();
            dataSourceComponent.setDataSourceName(dataSourceName);
            dataSourceComponent.setSensorName(repositoryProduct.getMission());
            dataSourceComponent.setFetchMode(FetchMode.RESUME);
            dataSourceComponent.setUserName(credentials.getUserPrincipal().getName());
            dataSourceComponent.setPassword(credentials.getPassword());
            dataSourceComponent.setProgressListener(new TAODownloadProductProgressListener(progressListener));
            dataSourceComponent.setProductStatusListener(new ProductStatusListener() {
                @Override
                public boolean downloadStarted(EOProduct eoProduct) {
                    eoProduct.setProductStatus(ProductStatus.DOWNLOADING);
                    return true;
                }

                @Override
                public void downloadCompleted(EOProduct eoProduct) {
                    eoProduct.setProductStatus(ProductStatus.DOWNLOADED);
                }

                @Override
                public void downloadFailed(EOProduct eoProduct, String message) {
                    eoProduct.setProductStatus(ProductStatus.FAILED);
                    downloadMessages.add(message);
                }

                @Override
                public void downloadAborted(EOProduct eoProduct, String message) {
                    downloadMessages.add(message);
                }

                @Override
                public void downloadIgnored(EOProduct eoProduct, String message) {
                    downloadMessages.add(message);
                }
            });

            EOProduct product = new EOProduct();
            product.setProductType(repositoryProduct.getMission());
            product.setName(repositoryProduct.getName());
            product.setLocation(repositoryProduct.getURL());

            List<EOProduct> products = new ArrayList<>(1);
            products.add(product); // add the product to be downloaded

            Properties additionalProperties = new Properties();
            additionalProperties.put("auto.uncompress", "true");

            dataSourceComponent.doFetch(products, null, targetFolderPath.toString(), null, additionalProperties);

            if (product.getProductStatus() == ProductStatus.DOWNLOADED) {
                String productPath = product.getLocation();
                if (productPath == null) {
                    throw new NullPointerException("The path of the downloaded product '" + repositoryProduct.getName()+"' is null.");
                }
                URI uri = new URI(productPath);
                return Paths.get(uri);
            } else {
                throw new IllegalStateException(buildFailedDownloadExceptionMessage(repositoryProduct.getName(), dataSourceName, repositoryProduct.getMission(), downloadMessages));
            }
        } finally {
            if (dataSourceComponent != null) {
                synchronized (this.downloadingProducts) {
                    this.downloadingProducts.remove(key);
                }
            }
        }
    }

    public static int getMaximumAllowedTransfers(String dataSourceName) {
        return findDataSource(dataSourceName).getMaximumAllowedTransfers();
    }

    public static boolean requiresAuthentication(String dataSourceName) {
        return findDataSource(dataSourceName).requiresAuthentication();
    }

    public static String[] getAvailableMissions(String dataSourceName) {
        return findDataSource(dataSourceName).getSupportedSensors();
    }

    public static BufferedImage downloadProductQuickLookImage(String dataSourceName, Credentials credentials, String url, ThreadStatus thread) throws IOException, InterruptedException {
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "Download the quick look image: remote repository '" + dataSourceName+"', url '"+url+"'.");
        }

        try (CloseableHttpResponse response = NetUtils.openConnection(HttpMethod.GET, url, credentials)) {
            if (response != null) {

                ThreadStatus.checkCancelled(thread);

                StatusLine statusLine = response.getStatusLine();
                switch (statusLine.getStatusCode()) {
                    case HttpURLConnection.HTTP_OK:
                        HttpEntity entity = response.getEntity();
                        InputStream inputStream = entity.getContent();
                        if (inputStream == null) {
                            return null;
                        }
                        try {
                            ThreadStatus.checkCancelled(thread);

                            ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream);
                            if (imageInputStream == null) {
                                return null;
                            }

                            ThreadStatus.checkCancelled(thread);

                            BufferedImage bufferedImage = ImageIO.read(imageInputStream);
                            if (bufferedImage == null) {
                                imageInputStream.close();
                            }
                            return bufferedImage;
                        } finally {
                            inputStream.close();
                        }
                    case HttpURLConnection.HTTP_UNAUTHORIZED:
                        throw new HTTPServerException(HttpURLConnection.HTTP_UNAUTHORIZED, "Unauthorized or the supplied credentials are invalid.");
                    case HttpURLConnection.HTTP_INTERNAL_ERROR:
                        throw new HTTPServerException(HttpURLConnection.HTTP_INTERNAL_ERROR, "Internal Server Error");
                    default:
                        throw new HTTPServerException(statusLine.getStatusCode(), String.valueOf(statusLine.getStatusCode()) + ": " + statusLine.getReasonPhrase());
                }
            } else {
                throw new IOException(String.format("Null response (maybe url %s is not reachable", url));
            }
        }
    }

    public static List<RepositoryQueryParameter> getMissionParameters(String dataSourceName, String mission) {
        DataSource dataSource = findDataSource(dataSourceName);
        Map<String, Map<ParameterName, DataSourceParameter>> supportedParameters = dataSource.getSupportedParameters();
        Map<ParameterName, DataSourceParameter> sensorParameters = supportedParameters.get(mission);
        Iterator<Map.Entry<ParameterName, DataSourceParameter>> it = sensorParameters.entrySet().iterator();
        List<RepositoryQueryParameter> parameters = new ArrayList<RepositoryQueryParameter>(sensorParameters.size());
        while (it.hasNext()) {
            Map.Entry<ParameterName, DataSourceParameter> entry = it.next();
            DataSourceParameter param = entry.getValue();
            Class<?> type;
            if (param.getName().equals(CommonParameterNames.FOOTPRINT)) {
                type = Rectangle2D.class;
            } else {
                type = param.getType();
            }
            boolean required = param.isRequired();
            if (!required) {
                if (param.getName().equals(CommonParameterNames.PLATFORM) || param.getName().equals(CommonParameterNames.START_DATE)
                        || param.getName().equals(CommonParameterNames.END_DATE) || param.getName().equals(CommonParameterNames.FOOTPRINT)) {

                    required = true;
                }
            }
            RepositoryQueryParameter queryParameter = new RepositoryQueryParameter(param.getName(), type, param.getLabel(), param.getDefaultValue(), required, param.getValueSet());
            parameters.add(queryParameter);
        }
        return parameters;
    }

    public static List<RepositoryProduct> downloadProductList(String dataSourceName, String mission, Credentials credentials, Map<String, Object> parameterValues,
                                                              ProductListDownloaderListener downloaderListener, ThreadStatus thread)
                                                              throws Exception {

        if (logger.isLoggable(Level.FINE)) {
            StringBuilder logMessage = new StringBuilder();
            logMessage.append("Start downloading the product list from the '")
                    .append(dataSourceName)
                    .append("' remote repository using the mission '")
                    .append(mission)
                    .append("'.\nThe query parameters are:");
            Iterator<Map.Entry<String, Object>> iterator = parameterValues.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Object> entry = iterator.next();
                logMessage.append("\n   - ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(entry.getValue());
            }
            logger.log(Level.FINE, logMessage.toString());
        }

        DataQuery query = buildDataQuery(dataSourceName, mission, credentials, parameterValues);
        query.setPageNumber(0);
        query.setPageSize(0);
        long totalProductCount = query.getCount();

        ThreadStatus.checkCancelled(thread);

        List<RepositoryProduct> productList;
        if (totalProductCount == 0) {
            downloaderListener.notifyProductCount(totalProductCount);

            productList = Collections.emptyList();
        } else if (totalProductCount > 0) {
            downloaderListener.notifyProductCount(totalProductCount);

            int pageSize = 100;
            long totalPageNumber = totalProductCount / pageSize;
            if (totalProductCount % pageSize != 0) {
                totalPageNumber++;
            }

            query.setPageSize(pageSize);

            WKTReader wktReader = new WKTReader();
            productList = new ArrayList<>();
            for (int pageNumber = 1; pageNumber <= totalPageNumber && productList.size() < totalProductCount; pageNumber++) {
                query.setPageNumber(pageNumber);

                ThreadStatus.checkCancelled(thread);

                List<EOProduct> pageResults = query.execute();

                ThreadStatus.checkCancelled(thread);

                List<RepositoryProduct> downloadedPageProducts = convertProducts(mission, pageResults, wktReader);
                productList.addAll(downloadedPageProducts);

                ThreadStatus.checkCancelled(thread);

                downloaderListener.notifyPageProducts(pageNumber, downloadedPageProducts, totalProductCount, productList.size());
            }
        } else {
            List<EOProduct> pageResults = query.execute();

            ThreadStatus.checkCancelled(thread);

            WKTReader wktReader = new WKTReader();
            List<RepositoryProduct> downloadedPageProducts = convertProducts(mission, pageResults, wktReader);
            productList = new ArrayList<>(downloadedPageProducts);

            downloaderListener.notifyPageProducts(1, downloadedPageProducts, productList.size(), productList.size());
        }
        return productList;
    }

    private static DataQuery buildDataQuery(String dataSourceName, String mission, Credentials credentials, Map<String, Object> parametersValues)
                                     throws InstantiationException, IllegalAccessException {

        DataSource dataSource = findDataSource(dataSourceName);
        if (credentials != null) {
            dataSource.setCredentials(credentials.getUserPrincipal().getName(), credentials.getPassword());
        }

        DataQuery query = dataSource.createQuery(mission);

        Iterator<Map.Entry<String, Object>> it = parametersValues.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> entry = it.next();
            String parameterName = entry.getKey();
            if (parameterName.equals(CommonParameterNames.FOOTPRINT)) {
                Rectangle2D selectionArea = (Rectangle2D) entry.getValue();

                ro.cs.tao.eodata.Polygon2D polygon2D = new ro.cs.tao.eodata.Polygon2D();
                polygon2D.append(selectionArea.getX(), selectionArea.getY()); // the top left corner
                polygon2D.append(selectionArea.getX() + selectionArea.getWidth(), selectionArea.getY()); // the top right corner
                polygon2D.append(selectionArea.getX() + selectionArea.getWidth(), selectionArea.getY() + selectionArea.getHeight()); // the bottom right corner
                polygon2D.append(selectionArea.getX(), selectionArea.getY() + selectionArea.getHeight()); // the bottom left corner
                polygon2D.append(selectionArea.getX(), selectionArea.getY()); // the top left corner
                query.addParameter(parameterName, polygon2D);
            } else if (!parameterName.equals(CommonParameterNames.START_DATE) && !parameterName.equals(CommonParameterNames.END_DATE)) {
                query.addParameter(parameterName, entry.getValue());
            }
        }

        Date startDate = (Date)parametersValues.get(CommonParameterNames.START_DATE);
        Date endDate = (Date)parametersValues.get(CommonParameterNames.END_DATE);
        if (startDate != null) {
            QueryParameter<Date> queryParameter = query.createParameter(CommonParameterNames.START_DATE, Date.class);
            queryParameter.setMinValue(startDate);
            if (endDate != null) {
                queryParameter.setMaxValue(endDate);
            }
            query.addParameter(queryParameter);
        }
        if (endDate != null) {
            QueryParameter<Date> queryParameter = query.createParameter(CommonParameterNames.END_DATE, Date.class);
            queryParameter.setMaxValue(endDate);
            if (startDate != null) {
                queryParameter.setMinValue(startDate);
            }
            query.addParameter(queryParameter);
        }

        return query;
    }

    private static List<RepositoryProduct> convertProducts(String mission, List<EOProduct> pageResults, WKTReader wktReader) throws InterruptedException, ParseException {
        List<RepositoryProduct> downloadedPageProducts = new ArrayList<>(pageResults.size());
        for (int i=0; i<pageResults.size(); i++) {
            EOProduct product = pageResults.get(i);
            Geometry productGeometry = wktReader.read(product.getGeometry());
            Coordinate[] coordinates;
            if (productGeometry instanceof MultiPolygon) {
                MultiPolygon multiPolygon = (MultiPolygon)productGeometry;
                if (multiPolygon.getNumGeometries() > 0) {
                    if (multiPolygon.getGeometryN(0) instanceof Polygon) {
                        coordinates = ((Polygon)multiPolygon.getGeometryN(0)).getExteriorRing().getCoordinates();
                    } else {
                        throw new IllegalStateException("The multipolygon first geometry is not a polygon.");
                    }
                } else {
                    throw new IllegalStateException("The product multipolygon geometry is empty.");
                }
            } else if (productGeometry instanceof Polygon) {
                coordinates = ((Polygon)productGeometry).getExteriorRing().getCoordinates();
            } else {
                throw new IllegalStateException("The product geometry type '"+productGeometry.getClass().getName()+"' is not a '"+Polygon.class.getName()+"' type.");
            }
            Coordinate firstCoordinate = coordinates[0];
            Coordinate lastCoordinate = coordinates[coordinates.length-1];
            if (firstCoordinate.getX() != lastCoordinate.getX() || firstCoordinate.getY() != lastCoordinate.getY()) {
                throw new IllegalStateException("The first and last coordinates of the polygon do not match.");
            }
            Polygon2D polygon = new Polygon2D();
            for (Coordinate coordinate : coordinates) {
                polygon.append(coordinate.getX(), coordinate.getY());
            }

            List<ro.cs.tao.eodata.Attribute> remoteAttributes = product.getAttributes();
            List<Attribute> attributes = new ArrayList<>(remoteAttributes.size());
            for (int k=0; k<remoteAttributes.size(); k++) {
                ro.cs.tao.eodata.Attribute remoteAttribute = remoteAttributes.get(k);
                attributes.add(new Attribute(remoteAttribute.getName(), remoteAttribute.getValue()));
            }

            TAORepositoryProduct repositoryProduct = new TAORepositoryProduct(product.getName(), product.getLocation(), mission, polygon, product.getAcquisitionDate(), product.getApproximateSize());
            repositoryProduct.setAttributes(attributes);
            repositoryProduct.setEntryPoint(product.getEntryPoint());
            repositoryProduct.setDataFormatType(convertToDataFormatType(product.getFormatType()));
            repositoryProduct.setPixelType(convertToPixelType(product.getPixelType()));
            repositoryProduct.setSensorType(convertToSensorType(product.getSensorType()));
            repositoryProduct.setDownloadQuickLookImageURL(product.getQuicklookLocation());

            downloadedPageProducts.add(repositoryProduct);
        }
        return downloadedPageProducts;
    }

    private static String buildKey(String dataSourceName, RepositoryProduct repositoryProduct) {
        return dataSourceName + "|" + repositoryProduct.getMission() + "|" + repositoryProduct.getName();
    }

    private static DataFormatType convertToDataFormatType(ro.cs.tao.eodata.enums.DataFormat dataFormat) {
        if (dataFormat == ro.cs.tao.eodata.enums.DataFormat.RASTER) {
            return DataFormatType.RASTER;
        }
        if (dataFormat == ro.cs.tao.eodata.enums.DataFormat.VECTOR) {
            return DataFormatType.VECTOR;
        }
        if (dataFormat == ro.cs.tao.eodata.enums.DataFormat.OTHER) {
            return DataFormatType.OTHER;
        }
        throw new IllegalArgumentException("Unknown data format: "+ dataFormat);
    }

    private static SensorType convertToSensorType(ro.cs.tao.eodata.enums.SensorType sensorType) {
        if (sensorType == ro.cs.tao.eodata.enums.SensorType.OPTICAL) {
            return SensorType.OPTICAL;
        }
        if (sensorType == ro.cs.tao.eodata.enums.SensorType.RADAR) {
            return SensorType.RADAR;
        }
        if (sensorType == ro.cs.tao.eodata.enums.SensorType.ALTIMETRIC) {
            return SensorType.ALTIMETRIC;
        }
        if (sensorType == ro.cs.tao.eodata.enums.SensorType.ATMOSPHERIC) {
            return SensorType.ATMOSPHERIC;
        }
        if (sensorType == ro.cs.tao.eodata.enums.SensorType.UNKNOWN) {
            return SensorType.UNKNOWN;
        }
        throw new IllegalArgumentException("Unknown sensor type: "+ sensorType);
    }

    private static PixelType convertToPixelType(ro.cs.tao.eodata.enums.PixelType pixelType) {
        if (pixelType == ro.cs.tao.eodata.enums.PixelType.UINT8) {
            return PixelType.UINT8;
        }
        if (pixelType == ro.cs.tao.eodata.enums.PixelType.UINT8) {
            return PixelType.UINT8;
        }
        if (pixelType == ro.cs.tao.eodata.enums.PixelType.INT8) {
            return PixelType.INT8;
        }
        if (pixelType == ro.cs.tao.eodata.enums.PixelType.UINT16) {
            return PixelType.UINT16;
        }
        if (pixelType == ro.cs.tao.eodata.enums.PixelType.UINT32) {
            return PixelType.UINT32;
        }
        if (pixelType == ro.cs.tao.eodata.enums.PixelType.INT32) {
            return PixelType.INT32;
        }
        if (pixelType == ro.cs.tao.eodata.enums.PixelType.FLOAT32) {
            return PixelType.FLOAT32;
        }
        if (pixelType == ro.cs.tao.eodata.enums.PixelType.FLOAT64) {
            return PixelType.FLOAT64;
        }
        throw new IllegalArgumentException("Unknown pixel type: "+ pixelType);
    }

    private static DataSource findDataSource(String dataSourceName) {
        Set<DataSource> services = DataSourceManager.getInstance().getRegisteredDataSources();
        for (DataSource dataSource : services) {
            if (dataSource.getId().equals(dataSourceName)) {
                return dataSource;
            }
        }
        throw new IllegalStateException("Unknown data source '" + dataSourceName + "'.");
    }

    private static String buildFailedDownloadExceptionMessage(String productName, String dataSourceName, String mission, List<String> downloadMessages) {
        StringBuilder exceptionMessage = new StringBuilder();
        exceptionMessage.append("Downloading the product '")
                .append(productName)
                .append("' from the data source '")
                .append(dataSourceName)
                .append("' and the mission '")
                .append(mission)
                .append("' has failed.");
        if (downloadMessages.size() > 0) {
            exceptionMessage.append(" Possible causes:");
            for (int i=0;i<downloadMessages.size(); i++) {
                if (i > 0) {
                    exceptionMessage.append("; ");
                }
                exceptionMessage.append(downloadMessages.get(i));
            }
        }
        return exceptionMessage.toString();
    }
}