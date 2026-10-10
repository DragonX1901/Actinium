package net.vulkanmod.render.chunk.util;

import java.util.Iterator;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;

public class StaticQueue<T> implements Iterable<T> {
   final T[] queue;
   int position = 0;
   int limit = 0;
   final int capacity;

   public StaticQueue() {
      this(1024);
   }

   public StaticQueue(int initialCapacity) {
      this.capacity = initialCapacity;
      this.queue = (T[])(new Object[this.capacity]);
   }

   public boolean hasNext() {
      return this.position < this.limit;
   }

   public T poll() {
      T t = this.queue[this.position];
      this.position++;
      return t;
   }

   public void add(T t) {
      this.queue[this.limit++] = t;
   }

   public int size() {
      return this.limit;
   }

   public void clear() {
      this.position = 0;
      this.limit = 0;
   }

   public Iterator<T> iterator(boolean reverseOrder) {
      return reverseOrder ? new Iterator<T>() {
         int pos;
         final int limit;

         {
            this.pos = StaticQueue.this.limit - 1;
            this.limit = -1;
         }

         @Override
         public boolean hasNext() {
            return this.pos > -1;
         }

         @Override
         public T next() {
            return StaticQueue.this.queue[this.pos--];
         }
      } : new Iterator<T>() {
         int pos = 0;
         final int limit;

         {
            this.limit = StaticQueue.this.limit;
         }

         @Override
         public boolean hasNext() {
            return this.pos < this.limit;
         }

         @Override
         public T next() {
            return StaticQueue.this.queue[this.pos++];
         }
      };
   }

   @NotNull
   @Override
   public Iterator<T> iterator() {
      return this.iterator(false);
   }

   @Override
   public void forEach(Consumer<? super T> action) {
      for (int i = 0; i < this.limit; i++) {
         action.accept(this.queue[i]);
      }
   }
}
