/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.eclipse.imagen.media.mlib;
import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.util.Map;

import org.eclipse.imagen.ImageLayout;
import org.eclipse.imagen.PointOpImage;

// import org.eclipse.imagen.media.test.OpImageTester;
import com.sun.medialib.mlib.Image;
import com.sun.medialib.mlib.mediaLibImage;

/**
 * An OpImage that performs the Not operation on 2 images through mediaLib.
 *
 */
final class MlibNotOpImage extends PointOpImage {

    /**
     * Constructs an MlibNotOpImage. The image dimensions are copied
     * from the source image.  The tile grid layout, SampleModel, and
     * ColorModel may optionally be specified by an ImageLayout object.
     *
     * @param source    a RenderedImage.
     * @param layout    an ImageLayout optionally containing the tile
     *                  grid layout, SampleModel, and ColorModel, or null.
     */
    public MlibNotOpImage(RenderedImage source,
                          Map config,
                          ImageLayout layout) {
	super(source, layout, config, true);
        // Set flag to permit in-place operation.
        permitInPlaceOperation();
    }

    /**
     * Not the pixel values of a rectangle from the source.
     * The source is cobbled.
     *
     * @param sources   an array of sources, guarantee to provide all
     *                  necessary source data for computing the rectangle.
     * @param dest      a tile that contains the rectangle to be computed.
     * @param destRect  the rectangle within this OpImage to be processed.
     */
    protected void computeRect(Raster[] sources,
                               WritableRaster dest,
                               Rectangle destRect) {

        int formatTag = MediaLibAccessor.findCompatibleTag(sources,dest);

        MediaLibAccessor srcAccessor = new MediaLibAccessor(sources[0],
							    destRect,formatTag);
        MediaLibAccessor dstAccessor = new MediaLibAccessor(dest,
							    destRect, formatTag);

        switch (dstAccessor.getDataType()) {
        case DataBuffer.TYPE_BYTE:
        case DataBuffer.TYPE_USHORT:
        case DataBuffer.TYPE_SHORT:
        case DataBuffer.TYPE_INT:
            mediaLibImage[] srcML = srcAccessor.getMediaLibImages();
            mediaLibImage[] dstML = dstAccessor.getMediaLibImages();
            for (int i = 0 ; i < dstML.length; i++) {
                Image.Not(dstML[i], srcML[i]);
            }
            break;
        default:
            String className = this.getClass().getName();
            throw new RuntimeException(className + JaiI18N.getString("Generic2"));
        }

        if (dstAccessor.isDataCopy()) {
            dstAccessor.clampDataArrays();
            dstAccessor.copyDataToRaster();
        }
    }

//     public static void main (String args[]) {
//         System.out.println("MlibNotOpImage Test");
//         ImageLayout layout;
//         OpImage src, dst;
//         Rectangle rect = new Rectangle(0, 0, 5, 5);

//         System.out.println("1. PixelInterleaved byte 3-band");
//         layout = OpImageTester.createImageLayout(0, 0, 800, 800, 0, 0,
// 						 200, 200, DataBuffer.TYPE_BYTE,
// 						 3, false);
//         src = OpImageTester.createRandomOpImage(layout);
//         dst = new MlibNotOpImage(src, null, null);
//         OpImageTester.testOpImage(dst, rect);
//         OpImageTester.timeOpImage(dst, 10);

//         System.out.println("2. Banded byte 3-band");
//         layout = OpImageTester.createImageLayout(0, 0, 800, 800, 0, 0,
// 						 200, 200, DataBuffer.TYPE_BYTE,
// 						 3, true);
//         src = OpImageTester.createRandomOpImage(layout);
//         dst = new MlibNotOpImage(src, null, null);
//         OpImageTester.testOpImage(dst, rect);
//         OpImageTester.timeOpImage(dst, 10);

//         System.out.println("3. PixelInterleaved int 3-band");
//         layout = OpImageTester.createImageLayout(0, 0, 512, 512, 0, 0, 200, 200,
// 						 DataBuffer.TYPE_INT, 3, false);
//         src = OpImageTester.createRandomOpImage(layout);
//         dst = new MlibNotOpImage(src, null, null);
//         OpImageTester.testOpImage(dst, rect);
//         OpImageTester.timeOpImage(dst, 10);

//         System.out.println("4. Banded int 3-band");
//         layout = OpImageTester.createImageLayout(0, 0, 512, 512, 0, 0,
// 						 200, 200, DataBuffer.TYPE_INT,
// 						 3, true);
//         src = OpImageTester.createRandomOpImage(layout);
//         dst = new MlibNotOpImage(src, null, null);
//         OpImageTester.testOpImage(dst, rect);
//         OpImageTester.timeOpImage(dst, 10);
//     }
}
