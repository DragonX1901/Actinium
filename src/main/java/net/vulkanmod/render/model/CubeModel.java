package net.vulkanmod.render.model;

import java.util.Set;
import net.minecraft.client.model.geom.ModelPart.Polygon;
import net.minecraft.client.model.geom.ModelPart.Vertex;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class CubeModel {
   private Polygon[] polygons = new Polygon[6];
   private int[] polygonAxes = new int[0];
   public float minX;
   public float minY;
   public float minZ;
   public float maxX;
   public float maxY;
   public float maxZ;
   Vector3f[] vertices;
   Vector3f[] transformed = new Vector3f[8];

   public void setVertices(
      int i, int j, float f, float g, float h, float k, float l, float m, float n, float o, float p, boolean bl, float q, float r, Set<Direction> set
   ) {
      this.minX = f;
      this.minY = g;
      this.minZ = h;
      this.maxX = f + k;
      this.maxY = g + l;
      this.maxZ = h + m;
      this.polygons = new Polygon[set.size()];
      float s = this.maxX;
      float t = this.maxY;
      float u = this.maxZ;
      f -= n;
      g -= o;
      h -= p;
      s += n;
      t += o;
      u += p;
      if (bl) {
         float v = s;
         s = f;
         f = v;
      }

      this.vertices = new Vector3f[]{
         new Vector3f(f, g, h),
         new Vector3f(s, g, h),
         new Vector3f(s, t, h),
         new Vector3f(f, t, h),
         new Vector3f(f, g, u),
         new Vector3f(s, g, u),
         new Vector3f(s, t, u),
         new Vector3f(f, t, u)
      };

      for (int i1 = 0; i1 < 8; i1++) {
         this.vertices[i1].div(16.0F);
         this.transformed[i1] = new Vector3f(0.0F);
      }

      Vertex vertex1 = new Vertex(this.transformed[0], 0.0F, 0.0F);
      Vertex vertex2 = new Vertex(this.transformed[1], 0.0F, 8.0F);
      Vertex vertex3 = new Vertex(this.transformed[2], 8.0F, 8.0F);
      Vertex vertex4 = new Vertex(this.transformed[3], 8.0F, 0.0F);
      Vertex vertex5 = new Vertex(this.transformed[4], 0.0F, 0.0F);
      Vertex vertex6 = new Vertex(this.transformed[5], 0.0F, 8.0F);
      Vertex vertex7 = new Vertex(this.transformed[6], 8.0F, 8.0F);
      Vertex vertex8 = new Vertex(this.transformed[7], 8.0F, 0.0F);
      float w = i;
      float x = i + m;
      float y = i + m + k;
      float z = i + m + k + k;
      float aa = i + m + k + m;
      float ab = i + m + k + m + k;
      float ac = j;
      float ad = j + m;
      float ae = j + m + l;
      int idx = 0;
      if (set.contains(Direction.DOWN)) {
         this.polygons[idx++] = new Polygon(new Vertex[]{vertex6, vertex5, vertex1, vertex2}, x, ac, y, ad, q, r, bl, Direction.DOWN);
      }

      if (set.contains(Direction.UP)) {
         this.polygons[idx++] = new Polygon(new Vertex[]{vertex3, vertex4, vertex8, vertex7}, y, ad, z, ac, q, r, bl, Direction.UP);
      }

      if (set.contains(Direction.WEST)) {
         this.polygons[idx++] = new Polygon(new Vertex[]{vertex1, vertex5, vertex8, vertex4}, w, ad, x, ae, q, r, bl, Direction.WEST);
      }

      if (set.contains(Direction.NORTH)) {
         this.polygons[idx++] = new Polygon(new Vertex[]{vertex2, vertex1, vertex4, vertex3}, x, ad, y, ae, q, r, bl, Direction.NORTH);
      }

      if (set.contains(Direction.EAST)) {
         this.polygons[idx++] = new Polygon(new Vertex[]{vertex6, vertex2, vertex3, vertex7}, y, ad, aa, ae, q, r, bl, Direction.EAST);
      }

      if (set.contains(Direction.SOUTH)) {
         this.polygons[idx] = new Polygon(new Vertex[]{vertex5, vertex6, vertex7, vertex8}, aa, ad, ab, ae, q, r, bl, Direction.SOUTH);
      }

      this.polygonAxes = new int[this.polygons.length];
      for (int face = 0; face < this.polygons.length; face++) {
         this.polygonAxes[face] = axisOf(this.polygons[face].normal);
      }
   }

   public void transformVertices(Matrix4f matrix) {
      Vector3f origin = this.vertices[0];
      float dx = this.vertices[1].x - origin.x;
      float dy = this.vertices[3].y - origin.y;
      float dz = this.vertices[4].z - origin.z;
      float x0 = Math.fma(matrix.m00(), origin.x, Math.fma(matrix.m10(), origin.y, Math.fma(matrix.m20(), origin.z, matrix.m30())));
      float y0 = Math.fma(matrix.m01(), origin.x, Math.fma(matrix.m11(), origin.y, Math.fma(matrix.m21(), origin.z, matrix.m31())));
      float z0 = Math.fma(matrix.m02(), origin.x, Math.fma(matrix.m12(), origin.y, Math.fma(matrix.m22(), origin.z, matrix.m32())));
      float xx = matrix.m00() * dx;
      float xy = matrix.m01() * dx;
      float xz = matrix.m02() * dx;
      float yx = matrix.m10() * dy;
      float yy = matrix.m11() * dy;
      float yz = matrix.m12() * dy;
      float zx = matrix.m20() * dz;
      float zy = matrix.m21() * dz;
      float zz = matrix.m22() * dz;
      Vector3f[] out = this.transformed;
      out[0].set(x0, y0, z0);
      out[1].set(x0 + xx, y0 + xy, z0 + xz);
      out[2].set(x0 + xx + yx, y0 + xy + yy, z0 + xz + yz);
      out[3].set(x0 + yx, y0 + yy, z0 + yz);
      out[4].set(x0 + zx, y0 + zy, z0 + zz);
      out[5].set(x0 + xx + zx, y0 + xy + zy, z0 + xz + zz);
      out[6].set(x0 + xx + yx + zx, y0 + xy + yy + zy, z0 + xz + yz + zz);
      out[7].set(x0 + yx + zx, y0 + yy + zy, z0 + yz + zz);
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

   public int[] getPolygonAxes() {
      return this.polygonAxes;
   }

   public Polygon[] getPolygons() {
      return this.polygons;
   }
}
