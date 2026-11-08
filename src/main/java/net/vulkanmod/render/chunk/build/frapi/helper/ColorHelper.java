package net.vulkanmod.render.chunk.build.frapi.helper;

import java.nio.ByteOrder;

public abstract class ColorHelper {
   private static final boolean BIG_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;

   private ColorHelper() {
   }

   public static int multiplyColor(int color1, int color2) {
      if (color1 == -1) {
         return color2;
      }

      if (color2 == -1) {
         return color1;
      }

      int alpha = (color1 >>> 24 & 0xFF) * (color2 >>> 24 & 0xFF) / 255;
      int red = (color1 >>> 16 & 0xFF) * (color2 >>> 16 & 0xFF) / 255;
      int green = (color1 >>> 8 & 0xFF) * (color2 >>> 8 & 0xFF) / 255;
      int blue = (color1 & 0xFF) * (color2 & 0xFF) / 255;
      return alpha << 24 | red << 16 | green << 8 | blue;
   }

   public static int multiplyRGB(int color, float shade) {
      int alpha = color >>> 24 & 0xFF;
      int red = (int)((color >>> 16 & 0xFF) * shade);
      int green = (int)((color >>> 8 & 0xFF) * shade);
      int blue = (int)((color & 0xFF) * shade);
      return alpha << 24 | red << 16 | green << 8 | blue;
   }

   public static int maxBrightness(int b0, int b1) {
      if (b0 == 0) {
         return b1;
      } else {
         return b1 == 0 ? b0 : Math.max(b0 & 65535, b1 & 65535) | Math.max(b0 & -65536, b1 & -65536);
      }
   }

   public static int toVanillaColor(int color) {
      if (color == -1) {
         return -1;
      } else {
         return BIG_ENDIAN ? (color & 16777215) << 8 | (color & 0xFF000000) >>> 24 : color & -16711936 | (color & 0xFF0000) >>> 16 | (color & 0xFF) << 16;
      }
   }

   public static int fromVanillaColor(int color) {
      if (color == -1) {
         return -1;
      } else {
         return BIG_ENDIAN ? (color & -256) >>> 8 | (color & 0xFF) << 24 : color & -16711936 | (color & 0xFF0000) >>> 16 | (color & 0xFF) << 16;
      }
   }
}
