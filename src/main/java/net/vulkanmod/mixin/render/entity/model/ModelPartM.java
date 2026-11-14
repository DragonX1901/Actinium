package net.vulkanmod.mixin.render.entity.model;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.ModelPart.Cube;
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
   @Unique
   private static final long ENTITY_STRIDE = 36L;
   @Unique
   private static final ThreadLocal<float[]> CORNERS = ThreadLocal.withInitial(() -> new float[24]);
   @Unique
   private int[] axisNormals;
   @Unique
   private Matrix3f lastNormal;
   @Unique
   private Vector3f scratchNormal;

   @Unique
   private int packAxis(float x, float y, float z, float sign) {
      float length = (float)Math.sqrt(x * x + y * y + z * z);
      float scale = length > 0.0F ? sign / length : 0.0F;
      return I32_SNorm.packNormal(x * scale, y * scale, z * scale);
   }

   @Unique
   private int transformedNormal(Vector3f n, Matrix3f matrix3f) {
      Vector3f normal = this.scratchNormal;
      if (normal == null) {
         normal = this.scratchNormal = new Vector3f();
      }

      matrix3f.transform(normal.set(n));
      normal.normalize();
      return I32_SNorm.packNormal(normal.x(), normal.y(), normal.z());
   }

   @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
   private void injCompile(Pose pose, VertexConsumer vertexConsumer, int light, int overlay, int color, CallbackInfo ci) {
      ExtendedVertexBuilder vertexBuilder = ExtendedVertexBuilder.of(vertexConsumer);
      if (vertexBuilder != null && vertexBuilder.canUseFastVertex()) {
         this.renderCubes(pose, vertexBuilder, light, overlay, color);
         ci.cancel();
      }
   }

   @Unique
   private void renderCubes(Pose pose, ExtendedVertexBuilder vertexBuilder, int light, int overlay, int color) {
      Matrix4f matrix4f = pose.pose();
      Matrix3f matrix3f = pose.normal();
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

      float[] corners = CORNERS.get();
      for (Cube cube : this.cubes) {
         CubeModel model = ((ModelPartCubeMixed)cube).getCubeModel();
         int faces = model.faceCount();
         if (faces == 0) {
            continue;
         }

         if (corners.length < model.cornerCount() * 3) {
            corners = new float[model.cornerCount() * 3];
            CORNERS.set(corners);
         }

         model.transformCorners(matrix4f, corners);
         long ptr = vertexBuilder.beginEntityVertices(faces * 4);
         for (int face = 0; face < faces; face++) {
            int axis = model.axis(face);
            int packedNormal = axis >= 0 ? axes[axis] : this.transformedNormal(model.normal(face), matrix3f);
            for (int corner = 0; corner < 4; corner++) {
               int c = model.cornerIndex(face, corner) * 3;
               float u = model.u(face, corner);
               float v = model.v(face, corner);
               if (ptr != 0L) {
                  MemoryUtil.memPutFloat(ptr, corners[c]);
                  MemoryUtil.memPutFloat(ptr + 4L, corners[c + 1]);
                  MemoryUtil.memPutFloat(ptr + 8L, corners[c + 2]);
                  MemoryUtil.memPutInt(ptr + 12L, color);
                  MemoryUtil.memPutFloat(ptr + 16L, u);
                  MemoryUtil.memPutFloat(ptr + 20L, v);
                  MemoryUtil.memPutInt(ptr + 24L, overlay);
                  MemoryUtil.memPutInt(ptr + 28L, light);
                  MemoryUtil.memPutInt(ptr + 32L, packedNormal);
                  ptr += ENTITY_STRIDE;
               } else {
                  vertexBuilder.vertex(corners[c], corners[c + 1], corners[c + 2], color, u, v, overlay, light, packedNormal);
               }
            }
         }
      }
   }
}
