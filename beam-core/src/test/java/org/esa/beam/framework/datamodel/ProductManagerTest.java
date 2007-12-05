/*
 * $Id: ProductManagerTest.java,v 1.1.1.1 2006/09/11 08:16:51 norman Exp $
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

package org.esa.beam.framework.datamodel;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.util.Vector;

public class ProductManagerTest extends TestCase {

    private static final String _prodName = "TestProduct";
    private static final int _sceneWidth = 400;
    private static final int _sceneHeight = 300;

    private ProductManager _productManager;
    private Product _product1;
    private Product _product2;
    private Product _product3;

    public ProductManagerTest(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(ProductManagerTest.class);
    }

    /**
     * Initializytion for the tests.
     */
    protected void setUp() {
        _productManager = new ProductManager();
        _product1 = new Product("product1", _prodName, _sceneWidth, _sceneHeight);
        _product2 = new Product("product2", _prodName, _sceneWidth, _sceneHeight);
        _product3 = new Product("product3", _prodName, _sceneWidth, _sceneHeight);
    }

    protected void tearDown() {

    }

    public void testAddProduct() {
        final ProductManagerListener listener = new ProductManagerListener();
        _productManager.addListener(listener);

        _productManager.addProduct(_product1);

        assertEquals(1, _productManager.getNumProducts());
        assertSame(_product1, _productManager.getProductAt(0));
        assertSame(_product1, _productManager.getProduct("product1"));
        assertEquals(1, _product1.getRefNo());
        assertSame(_productManager, _product1.getProductManager());

        final Vector addedProducts = listener.getAddedProducts();
        assertEquals(1, addedProducts.size());
        assertSame(_product1, addedProducts.get(0));

        final Vector removedProducts = listener.getRemovedProducts();
        assertEquals(0, removedProducts.size());
    }

    public void testRemoveProduct() {
        addAllProducts();

        final ProductManagerListener listener = new ProductManagerListener();
        _productManager.addListener(listener);

        _productManager.removeProduct(_product2);

        assertEquals(2, _productManager.getNumProducts());
        assertSame(_product1, _productManager.getProductAt(0));
        assertSame(_product3, _productManager.getProductAt(1));
        assertSame(_product1, _productManager.getProduct("product1"));
        assertNull(_productManager.getProduct("product2"));
        assertSame(_product3, _productManager.getProduct("product3"));
        assertEquals(1, _product1.getRefNo());
        assertEquals(0, _product2.getRefNo());
        assertEquals(3, _product3.getRefNo());
        assertSame(_productManager, _product1.getProductManager());
        assertNull(_product2.getProductManager());
        assertSame(_productManager, _product3.getProductManager());

        final Vector addedProducts = listener.getAddedProducts();
        assertEquals(0, addedProducts.size());

        final Vector removedProducts = listener.getRemovedProducts();
        assertEquals(1, removedProducts.size());
        assertSame(_product2, removedProducts.get(0));
    }

    public void testRemoveAll() {
        addAllProducts();
        final ProductManagerListener listener = new ProductManagerListener();
        _productManager.addListener(listener);
        _productManager.removeAllProducts();

        assertEquals(0, _productManager.getNumProducts());

        assertNull(_product1.getProductManager());
        assertNull(_product2.getProductManager());
        assertNull(_product3.getProductManager());

        assertEquals(0, _product1.getRefNo());
        assertEquals(0, _product2.getRefNo());
        assertEquals(0, _product3.getRefNo());


        final Vector removedProducts = listener.getRemovedProducts();
        assertEquals(3, removedProducts.size());
        assertSame(_product1, removedProducts.get(0));
        assertSame(_product2, removedProducts.get(1));
        assertSame(_product3, removedProducts.get(2));

        final Vector addedProducts = listener.getAddedProducts();
        assertEquals(0, addedProducts.size());
    }

    public void testContainsProduct() {
        assertEquals(false, _productManager.containsProduct("product2"));

        _productManager.addProduct(_product2);
        assertEquals(true, _productManager.containsProduct("product2"));

        _productManager.removeProduct(_product2);
        assertEquals(false, _productManager.containsProduct("product2"));
    }

    public void testGetNumProducts() {
        assertEquals(0, _productManager.getNumProducts());
        addAllProducts();
        assertEquals(3, _productManager.getNumProducts());
        _productManager.removeProduct(_product1);
        assertEquals(2, _productManager.getNumProducts());
        _productManager.removeProduct(_product2);
        assertEquals(1, _productManager.getNumProducts());
        _productManager.removeProduct(_product2);
        assertEquals(1, _productManager.getNumProducts());
        _productManager.removeProduct(null);
        assertEquals(1, _productManager.getNumProducts());
        _productManager.removeProduct(_product3);
        assertEquals(0, _productManager.getNumProducts());
    }

    public void testGetProduct() {
        addAllProducts();

        assertSame(_product1, _productManager.getProductAt(0));
        assertSame(_product2, _productManager.getProductAt(1));
        assertSame(_product3, _productManager.getProductAt(2));
    }

    public void testGetProductNames() {
        addAllProducts();

        String[] names = _productManager.getProductNames();
        assertEquals(names[0], _product1.getName());
        assertEquals(names[1], _product2.getName());
        assertEquals(names[2], _product3.getName());
    }

    public void testAddProductsWithTheSameName() {
        final Product product1 = new Product("name", "t", 1, 1);
        final Product product2 = new Product("name", "t", 1, 1);
        final Product product3 = new Product("name", "t", 1, 1);

        _productManager.addProduct(product1);
        _productManager.addProduct(product2);
        _productManager.addProduct(product3);

        assertEquals(3, _productManager.getNumProducts());
        assertSame(product1, _productManager.getProductAt(0));
        assertSame(product2, _productManager.getProductAt(1));
        assertSame(product3, _productManager.getProductAt(2));
    }

    public void testGetProductDisplayNames() {
        final Product product1 = new Product("name", "t", 1, 1);
        final Product product2 = new Product("name", "t", 1, 1);
        final Product product3 = new Product("name", "t", 1, 1);

        _productManager.addProduct(product1);
        _productManager.addProduct(product2);
        _productManager.addProduct(product3);

        String[] names = _productManager.getProductDisplayNames();
        assertEquals(3, names.length);
        assertEquals("[1] name", names[0]);
        assertEquals("[2] name", names[1]);
        assertEquals("[3] name", names[2]);
    }

    public void testGetProductByDisplayName() {
        final Product product1 = new Product("name", "t", 1, 1);
        final Product product2 = new Product("name", "t", 1, 1);
        final Product product3 = new Product("name", "t", 1, 1);

        _productManager.addProduct(product1);
        _productManager.addProduct(product2);
        _productManager.addProduct(product3);

        assertEquals(3, _productManager.getNumProducts());
        assertSame(product1, _productManager.getProductByDisplayName("[1] name"));
        assertSame(product2, _productManager.getProductByDisplayName("[2] name"));
        assertSame(product3, _productManager.getProductByDisplayName("[3] name"));
    }

    private void addAllProducts() {
        _productManager.addProduct(_product1);
        _productManager.addProduct(_product2);
        _productManager.addProduct(_product3);
    }

    private class ProductManagerListener implements ProductManager.ProductManagerListener {

        private Vector _addedProducts = new Vector();
        private Vector _removedProducts = new Vector();

        public void productAdded(ProductManager.Event event) {
            _addedProducts.add(event.getProduct());
        }

        public void productRemoved(ProductManager.Event event) {
            _removedProducts.add(event.getProduct());
        }

        public Vector getAddedProducts() {
            return _addedProducts;
        }

        public Vector getRemovedProducts() {
            return _removedProducts;
        }
    }
}
