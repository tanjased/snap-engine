package org.esa.snap.product.library.v2.activator;

import org.esa.snap.product.library.v2.database.DataAccess;
import org.esa.snap.runtime.Activator;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The activator class used to initialize the H2 local database of the Product Library Tool.
 *
 * Created by jcoravu on 3/9/2019.
 */
public class ProductLibraryActivator implements Activator {

    private static final Logger logger = Logger.getLogger(ProductLibraryActivator.class.getName());

    public ProductLibraryActivator() {
    }

    @Override
    public void start() {
        try {
            // load H2 driver
            Class.forName("org.h2.Driver");
            DataAccess.initialize();
            DataAccess.upgradeDatabase();
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Failed to initialize the database.", exception);
        }
    }

    @Override
    public void stop() {
        // do nothing
    }
}