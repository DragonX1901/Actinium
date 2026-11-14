package net.vulkanmod.mixin.render.entity.model;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.ModelPart.Cube;
import net.minecraft.client.model.geom.ModelPart.Polygon;
import net.minecraft.client.model.geom.ModelPart.Vertex;
import net.vulkanmod.interfaces.ExtendedVertexBuilder;
import net.vulkanmod.interfaces.ModelPartCubeMixed;
import net.vulkanmod.render.model.CubeModel;
import net.vulkanmod.render.vertex.format.I32_SNorm;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.class)
public abstract class ModelPartM {
   @Shadow
   @Final
   private List<Cube> cubes;
   Vector3f normal = new Vector3f();
   @Unique
   private static final long ENTITY_STRIDE = 36L;
   @Unique
   private int[] axisNormals;
   @Unique
   private Matrix3f lastNormal;

   @Unique
   private int packedNormal(Vector3f n, Matrix3f matrix3f, int[] axes) {
      if (n.y == 0.0F && n.z == 0.0F && Math.abs(n.x) == 1.0F) {
         return axes[n.x > 0.0F ? 0 : 1];
      } else if (n.x == 0.0F && n.z == 0.0F && Math.abs(n.y) == 1.0F) {
         return axes[n.y > 0.0F ? 2 : 3];
      } else if (n.x == 0.0F && n.y == 0.0F && Math.abs(n.z) == 1.0F) {
         return axes[n.z > 0.0F ? 4 : 5];
      }

      matrix3f.transform(this.normal.set(n));
      this.normal.normalize();
      return I32_SNorm.packNormal(this.normal.x(), this.normal.y(), this.normal.z());
   }

   @Unique
   private int packAxis(float x, float y, float z, float sign) {
      float length = (float)Math.sqrt(x * x + y * y + z * z);
      float scale = length > 0.0F ? sign / length : 0.0F;
      return I32_SNorm.packNormal(x * scale, y * scale, z * scale);
   }

   @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
   private void injCompile(Pose pose, VertexConsumer vertexConsumer, int light, int overlay, int color, CallbackInfo ci) {
      this.renderCubes(pose, vertexConsumer, light, overlay, color);
      ci.cancel();
   }

   @Unique
   public void renderCubes(Pose pose, VertexConsumer vertexConsumer, int light, int overlay, int color) {
      Matrix4f matrix4f = pose.pose();
      Matrix3f matrix3f = pose.normal();
      ExtendedVertexBuilder vertexBuilder = ExtendedVertexBuilder.of(vertexConsumer);
      if (this.normal == null) {
         this.normal = new Vector3f();
      }

      if (vertexBuilder != null && vertexBuilder.canUseFastVertex()) {
         color = ColorUtil.RGBA.fromArgb32(color);
         int[] axes = this.axisNormals;
         if (axes == null) {
            axes = this.axisNormals = new int[6];
            this.lastNormal = new Matrix3f().zero();
         }

         if (!matrix3f.equals(this.lastNormal)) {
            this.lastNormal.set(matrix3f);
            axes[0] = this.packAxis(matrix3f.m00, matrix3f.m01, matrix3f.m02, 1.0F);
            axes[1] = this.packAxis(matrix3f.m00, matrix3f.m01, matrix3f.m02, -1.0F);
            axes[2] = this.packAxis(matrix3f.m10, matrix3f.m11, matrix3f.m12, 1.0F);
            axes[3] = this.packAxis(matrix3f.m10, matrix3f.m11, matrix3f.m12, -1.0F);
            axes[4] = this.packAxis(matrix3f.m20, matrix3f.m21, matrix3f.m22, 1.0F);
            axes[5] = this.packAxis(matrix3f.m20, matrix3f.m21, matrix3f.m22, -1.0F);
         }

         for (Cube cube : this.cubes) {
            ModelPartCubeMixed cubeMixed = (ModelPartCubeMixed)cube;
            CubeModel cubeModel = cubeMixed.getCubeModel();
            Polygon[] polygons = cubeModel.getPolygons();
            if (polygons.length == 0) {
               continue;
            }

            cubeModel.transformVertices(matrix4f);
            long ptr = vertexBuilder.beginEntityVertices(polygons.length * 4);

            int[] polygonAxes = cubeModel.getPolygonAxes();
            for (int p = 0; p < polygons.length; p++) {
               Polygon polygon = polygons[p];
               int axis = polygonAxes[p];
               int packedNormal = axis >= 0 ? axes[axis] : this.packedNormal(polygon.normal, matrix3f, axes);
               Vertex[] vertices = polygon.vertices;
               if (ptr != 0L) {
                  for (Vertex vertex : vertices) {
                     Vector3f pos = vertex.pos;
                     MemoryUtil.memPutFloat(ptr, pos.x);
                     MemoryUtil.memPutFloat(ptr + 4L, pos.y);
                     MemoryUtil.memPutFloat(ptr + 8L, pos.z);
                     MemoryUtil.memPutInt(ptr + 12L, color);
                     MemoryUtil.memPutFloat(ptr + 16L, vertex.u);
                     MemoryUtil.memPutFloat(ptr + 20L, vertex.v);
                     MemoryUtil.memPutInt(ptr + 24L, overlay);
                     MemoryUtil.memPutInt(ptr + 28L, light);
                     MemoryUtil.memPutInt(ptr + 32L, packedNormal);
                     ptr += ENTITY_STRIDE;
                  }
               } else {
                  for (Vertex vertex : vertices) {
                     Vector3f pos = vertex.pos;
                     vertexBuilder.vertex(pos.x(), pos.y(), pos.z(), color, vertex.u, vertex.v, overlay, light, packedNormal);
                  }
               }
            }
         }
      } else {
         for (Cube cube : this.cubes) {
            ModelPartCubeMixed cubeMixed = (ModelPartCubeMixed)cube;
            CubeModel cubeModel = cubeMixed.getCubeModel();
            Polygon[] polygons = cubeModel.getPolygons();
            cubeModel.transformVertices(matrix4f);

            for (Polygon polygon : polygons) {
               matrix3f.transform(this.normal.set(polygon.normal));
               this.normal.normalize();
               Vertex[] vertices = polygon.vertices;

               for (Vertex vertex : vertices) {
                  Vector3f pos = vertex.pos;
                  vertexConsumer.addVertex(
                     pos.x(), pos.y(), pos.z(), color, vertex.u, vertex.v, overlay, light, this.normal.x(), this.normal.y(), this.normal.z()
                  );
               }
            }
         }
      }
   }
}
