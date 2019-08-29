package org.esa.snap.product.library.v2.repository;

import org.apache.http.auth.Credentials;
import org.esa.snap.product.library.v2.ProductsDownloaderListener;
import org.esa.snap.product.library.v2.RepositoryProduct;
import org.esa.snap.product.library.v2.ThreadStatus;
import org.esa.snap.product.library.v2.parameters.QueryFilter;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Created by jcoravu on 26/8/2019.
 */
public interface ProductsRepositoryProvider {

    public String getRepositoryName();

    public String getRepositoryId();

    public String[] getAvailableMissions();

    public List<QueryFilter> getMissionParameters(String mission);

    public List<RepositoryProduct> downloadProductList(Credentials credentials, String mission, Map<String, Object> parameterValues,
                                                       ProductsDownloaderListener downloaderListener, ThreadStatus thread)
            throws java.lang.InterruptedException;

    public BufferedImage downloadProductQuickLookImage(Credentials credentials, String url, ThreadStatus thread)
            throws IOException, java.lang.InterruptedException;

    public ProductRepositoryDownloader buidProductDownloader(String mission);
}
