package net.vulkanmod.render.chunk.build.frapi.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.Consumer;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.Mesh;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.MutableQuadView;
import net.vulkanmod.render.chunk.build.frapi.api.render.RenderContext;
import net.vulkanmod.render.chunk.build.frapi.api.render.RenderContext.QuadTransform;
import net.vulkanmod.render.chunk.build.frapi.mesh.MutableQuadViewImpl;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

abstract class AbstractRenderContext implements RenderContext {
   private static final QuadTransform NO_TRANSFORM = q -> true;
   private QuadTransform activeTransform = NO_TRANSFORM;
   private final ObjectArrayList<QuadTransform> transformStack = new ObjectArrayList();
   private final QuadTransform stackTransform = q -> {
      int i = this.transformStack.size() - 1;

      while (i >= 0) {
         if (!((QuadTransform)this.transformStack.get(i--)).transform(q)) {
            return false;
         }
      }

      return true;
   };
   @Deprecated
   private final Consumer<Mesh> meshConsumer = mesh -> mesh.outputTo(this.getEmitter());
   protected Matrix4f matrix;
   protected Matrix3f normalMatrix;
   protected int overlay;
   private final Vector4f posVec = new Vector4f();
   private final Vector3f normalVec = new Vector3f();

   protected final boolean transform(MutableQuadView q) {
      return this.activeTransform.transform(q);
   }

   public boolean hasTransform() {
      return this.activeTransform != NO_TRANSFORM;
   }

   public void pushTransform(QuadTransform transform) {
      if (transform == null) {
         throw new NullPointerException("Renderer received null QuadTransform.");
      }

      this.transformStack.push(transform);
      if (this.transformStack.size() == 1) {
         this.activeTransform = transform;
      } else if (this.transformStack.size() == 2) {
         this.activeTransform = this.stackTransform;
      }
   }

   public void popTransform() {
      this.transformStack.pop();
      if (this.transformStack.size() == 0) {
         this.activeTransform = NO_TRANSFORM;
      } else if (this.transformStack.size() == 1) {
         this.activeTransform = (QuadTransform)this.transformStack.get(0);
      }
   }

   @Deprecated
   public Consumer<Mesh> meshConsumer() {
      return this.meshConsumer;
   }

   protected void bufferQuad(MutableQuadViewImpl quad, VertexConsumer vertexConsumer) {
      Vector4f posVec = this.posVec;
      Vector3f normalVec = this.normalVec;
      boolean useNormals = quad.hasVertexNormals();
      if (useNormals) {
         quad.populateMissingNormals();
      } else {
         normalVec.set(quad.faceNormal());
         normalVec.mul(this.normalMatrix);
      }

      for (int i = 0; i < 4; i++) {
         posVec.set(quad.x(i), quad.y(i), quad.z(i), 1.0F);
         posVec.mul(this.matrix);
         vertexConsumer.addVertex(posVec.x(), posVec.y(), posVec.z());
         int color = quad.color(i);
         vertexConsumer.setColor(color);
         vertexConsumer.setUv(quad.u(i), quad.v(i));
         vertexConsumer.setOverlay(this.overlay);
         vertexConsumer.setLight(quad.lightmap(i));
         if (useNormals) {
            quad.copyNormal(i, normalVec);
            normalVec.mul(this.normalMatrix);
         }

         vertexConsumer.setNormal(normalVec.x(), normalVec.y(), normalVec.z());
      }
   }
}
