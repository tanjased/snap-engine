/*
 * Copyright (C) 2010 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.beam.dataio.avhrr.noaa;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.esa.beam.dataio.avhrr.AvhrrConstants;
import org.esa.beam.dataio.avhrr.HeaderUtil;
import org.esa.beam.framework.datamodel.MetadataElement;

class RadianceConversionHeader {
    private static final String META_DATA_NAME = "RADIANCE_CONVERSION";

    private float[] solarIrradiance;
    private float[] equivalentWidth;
    private float[] centralWavenumber;
    private float[] constant1;
    private float[] constant2;

    public RadianceConversionHeader(InputStream header) throws IOException {
        solarIrradiance = new float[3];
        equivalentWidth = new float[3];
        centralWavenumber = new float[3];
        constant1 = new float[3];
        constant2 = new float[3];
        parseHeader(header);
    }

    public float getSolarIrradiance(int channel) {
        return solarIrradiance[channel];
    }

    public float getEquivalentWidth(int channel) {
        return equivalentWidth[channel];
    }

    public float getCentralWavenumber(int channel) {
        return centralWavenumber[channel - AvhrrConstants.CH_3B];
    }

    public float getConstant1(int channel) {
        return constant1[channel - AvhrrConstants.CH_3B];
    }

    public float getConstant2(int channel) {
        return constant2[channel - AvhrrConstants.CH_3B];
    }

    public MetadataElement getMetadata() {
        MetadataElement element = new MetadataElement(META_DATA_NAME);
        for (int i = 0; i < 3; i++) {
            element.addAttribute(HeaderUtil.createAttribute(getNamePrefix(i) + "SOLAR_IRRADIANCE", solarIrradiance[i]));
        }
        for (int i = 0; i < 3; i++) {
            element.addAttribute(HeaderUtil.createAttribute(getNamePrefix(i) + "EQUIVALENT_WIDTH", equivalentWidth[i]));
        }
        for (int i = 0; i < 3; i++) {
            element.addAttribute(HeaderUtil.createAttribute(getNamePrefix(i + 3) + "CENTRAL_WAVENUMBER", centralWavenumber[i]));
        }
        for (int i = 0; i < 3; i++) {
            element.addAttribute(HeaderUtil.createAttribute(getNamePrefix(i + 3) + "CONSTANT_1", constant1[i]));
        }
        for (int i = 0; i < 3; i++) {
            element.addAttribute(HeaderUtil.createAttribute(getNamePrefix(i + 3) + "CONSTANT_2", constant2[i]));
        }
        return element;
    }

    private String getNamePrefix(int i) {
        return "CHANNEL_" + AvhrrConstants.CH_STRINGS[i].toUpperCase() + "_";
    }

    private void parseHeader(InputStream header) throws IOException {
        DataInputStream inStream = new DataInputStream(header);

        for (int i = 0; i < 3; i++) {
            solarIrradiance[i] = inStream.readInt() * 1E-1f;
            equivalentWidth[i] = inStream.readInt() * 1E-3f;
        }

        for (int i = 0; i < 3; i++) {
            float cwScaleFactor = (i == 0) ? 1E-2f : 1E-3f;
            centralWavenumber[i] = inStream.readInt() * cwScaleFactor;
            constant1[i] = inStream.readInt() * 1E-5f;
            constant2[i] = inStream.readInt() * 1E-6f;
        }
    }
}
