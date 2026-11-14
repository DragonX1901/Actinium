package net.vulkanmod.render.model;

import net.minecraft.client.model.geom.ModelPart.Polygon;
import net.minecraft.client.model.geom.ModelPart.Vertex;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class CubeModel {
   private static final byte[] NO_BYTES = new byte[0];
   private static final float[] NO_FLOATS = new float[0];

   private final Polygon[] polygons;
   private final float[] corners;
   private final byte[] cornerIndices;
   private final float[] uvs;
   private final byte[] axes;

   public CubeModel(Polygon[] polygons) {
      this.polygons = polygons;
      int faces = polygons.length;
      if (faces == 0) {
         this.corners = NO_FLOATS;
         this.cornerIndices = NO_BYTES;
         this.uvs = NO_FLOATS;
         this.axes = NO_BYTES;
         return;
      }

      float[] found = new float[24];
      int cornerCount = 0;
      byte[] indices = new byte[faces * 4];
      float[] coords = new float[faces * 8];
      byte[] faceAxes = new byte[faces];
      for (int face = 0; face < faces; face++) {
         Polygon polygon = polygons[face];
         faceAxes[face] = (byte) axisOf(polygon.normal);
         for (int corner = 0; corner < 4; corner++) {
            Vertex vertex = polygon.vertices[corner];
            float x = vertex.pos.x / 16.0F;
            float y = vertex.pos.y / 16.0F;
            float z = vertex.pos.z / 16.0F;
            int index = -1;
            for (int i = 0; i < cornerCount; i++) {
               if (found[i * 3] == x && found[i * 3 + 1] == y && found[i * 3 + 2] == z) {
                  index = i;
                  break;
               }
            }

            if (index < 0) {
               if (cornerCount == 8) {
                  float[] grown = new float[found.length + 12];
                  System.arraycopy(found, 0, grown, 0, found.length);
                  found = grown;
               }

               index = cornerCount++;
               found[index * 3] = x;
               found[index * 3 + 1] = y;
               found[index * 3 + 2] = z;
            }

            indices[face * 4 + corner] = (byte) index;
            coords[face * 8 + corner * 2] = vertex.u;
            coords[face * 8 + corner * 2 + 1] = vertex.v;
         }
      }

      float[] trimmed = new float[cornerCount * 3];
      System.arraycopy(found, 0, trimmed, 0, trimmed.length);
      this.corners = trimmed;
      this.cornerIndices = indices;
      this.uvs = coords;
      this.axes = faceAxes;
   }

   private static int axisOf(Vector3f n) {
      if (n.y == 0.0F && n.z == 0.0F && Math.abs(n.x) == 1.0F) {
         return n.x > 0.0F ? 0 : 1;
      } else if (n.x == 0.0F && n.z == 0.0F && Math.abs(n.y) == 1.0F) {
         return n.y > 0.0F ? 2 : 3;
      } else if (n.x == 0.0F && n.y == 0.0F && Math.abs(n.z) == 1.0F) {
         return n.z > 0.0F ? 4 : 5;
      }

      return -1;
   }

   public int faceCount() {
      return this.axes.length;
   }

   public int cornerCount() {
      return this.corners.length / 3;
   }

   public void transformCorners(Matrix4f matrix, float[] out) {
      float[] c = this.corners;
      float m00 = matrix.m00(), m01 = matrix.m01(), m02 = matrix.m02();
      float m10 = matrix.m10(), m11 = matrix.m11(), m12 = matrix.m12();
      float m20 = matrix.m20(), m21 = matrix.m21(), m22 = matrix.m22();
      float m30 = matrix.m30(), m31 = matrix.m31(), m32 = matrix.m32();
      for (int i = 0; i < c.length; i += 3) {
         float x = c[i];
         float y = c[i + 1];
         float z = c[i + 2];
         out[i] = Math.fma(m00, x, Math.fma(m10, y, Math.fma(m20, z, m30)));
         out[i + 1] = Math.fma(m01, x, Math.fma(m11, y, Math.fma(m21, z, m31)));
         out[i + 2] = Math.fma(m02, x, Math.fma(m12, y, Math.fma(m22, z, m32)));
      }
   }

   public int axis(int face) {
      return this.axes[face];
   }

   public Vector3f normal(int face) {
      return this.polygons[face].normal;
   }

   public int cornerIndex(int face, int corner) {
      return this.cornerIndices[face * 4 + corner];
   }

   public float u(int face, int corner) {
      return this.uvs[face * 8 + corner * 2];
   }

   public float v(int face, int corner) {
      return this.uvs[face * 8 + corner * 2 + 1];
   }
}
