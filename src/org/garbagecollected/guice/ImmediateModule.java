/**
 * Copyright (C) 2009 Robbie Vanbrabant.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.garbagecollected.guice;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedList;
import java.util.List;

import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import com.google.inject.binder.AnnotatedBindingBuilder;
import com.google.inject.binder.AnnotatedConstantBindingBuilder;
import com.google.inject.binder.LinkedBindingBuilder;

/**
 * Enables a slightly less cluttered syntax for creating instances of {@link Module},
 * by eliminating the need to override a {@code configure(...)} method when using
 * anonymous inner classes.
 * <p>
 * Before:
 * <pre>
 * Module module = new AbstractModule() {
 *   {@literal @}Override
 *   protected void configure() {
 *     bind(MyInterface.class).to(MyImplementation.class);
 *   }
 * };
 * </pre>
 * <p>
 * After:
 * <pre>
 * Module module = new ImmediateModule() {{
 *   bind(MyInterface.class).to(MyImplementation.class);
 * }};
 * </pre>
 * <p>
 * Note that by creating an ImmediateModule, you immediately execute your bind statements
 * (hence the module name). This means that any objects you create in your bindings will be created
 * right there on the spot, and not when Guice invokes {@link Module#configure(Binder)}.
 * Behind the scenes, ImmediateModule records what you do at module creation time
 * and then replays it when Guice calls {@link Module#configure(Binder)}.
 * 
 * @author Robbie Vanbrabant
 */
public abstract class ImmediateModule implements Module {
  private final LinkedList<Invocation> recordedInvocations = new LinkedList<Invocation>();
  
  // TODO Guice also has a RecordingBinder in the Elements class, maybe use?
  private final Binder recordingBinder = newRecordingBinder(recordedInvocations);

  /**
   * Only for Guice; to add bindings use the object initializer syntax.
   * @see Module#configure(Binder)
   */
  public final void configure(Binder binder) {
    if (recordedInvocations.size() == 0) {
      StringBuilder errorMsg = new StringBuilder(
          String.format("No bindings found, use the double bracket syntax to add them:%n"));
      errorMsg.append(String.format("%s module = new %1$s() {{%n", 
          ImmediateModule.class.getSimpleName()));
      errorMsg.append(String.format("  bind(MyClass.class);%n"));
      errorMsg.append(String.format("}};%n"));
      throw new IllegalStateException(errorMsg.toString());
    }
    replayInvocations(binder, binder, recordedInvocations.removeFirst());
  }
  
  void replayInvocations(Binder binder, Object target, Invocation invocation) {
    Object result = invocation.execute(invocation.root ? binder : target);
    if (recordedInvocations.peek() != null) 
      replayInvocations(binder, result, recordedInvocations.removeFirst());
  }
  
  /**
   * Gets direct access to the underlying {@code Binder}.
   */
  protected final Binder binder() {
    return recordingBinder;
  }

  /**
   * @see Binder#bind(Key)
   */
  protected final <T> LinkedBindingBuilder<T> bind(Key<T> key) {
    return recordingBinder.bind(key);
  }

  /**
   * @see Binder#bind(TypeLiteral)
   */
  protected final <T> AnnotatedBindingBuilder<T> bind(TypeLiteral<T> typeLiteral) {
    return recordingBinder.bind(typeLiteral);
  }

  /**
   * @see Binder#bind(Class)
   */
  protected final <T> AnnotatedBindingBuilder<T> bind(Class<T> clazz) {
    return recordingBinder.bind(clazz);
  }

  /**
   * @see Binder#bindConstant()
   */
  protected final AnnotatedConstantBindingBuilder bindConstant() {
    return recordingBinder.bindConstant();
  }

  /**
   * @see Binder#install(Module)
   */
  protected final void install(Module module) {
    recordingBinder.install(module);
  }

  /**
   * @see Binder#requestInjection(Object)
   * @since 2.0
   */
  protected final void requestInjection(Object instance) {
    recordingBinder.requestInjection(instance);
  }

  /**
   * @see Binder#requestStaticInjection(Class[])
   */
  protected final void requestStaticInjection(Class<?>... types) {
    recordingBinder.requestStaticInjection(types);
  }

  private static Binder newRecordingBinder(List<Invocation> invocations) {
    return (Binder) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
        new Class[] { Binder.class }, new MethodInvocationRecorder(true, invocations));
  }
  
  static class MethodInvocationRecorder implements InvocationHandler {
    private final List<Invocation> invocations;
    private final boolean root;
    
    MethodInvocationRecorder(boolean root, List<Invocation> invocations) {
      this.root = root;
      this.invocations = invocations;
    }
    
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (isObjectToString(method)) {
        return toString();
      } else if (isObjectHashCode(method)) {
        return hashCode();
      } else if (isObjectEquals(method)) {
        return equals(args[0]);
      } else { // something we care about
        Invocation incomingInvocation = new Invocation();
        incomingInvocation.method = method;
        incomingInvocation.args = args;
        incomingInvocation.root = root;
        invocations.add(incomingInvocation);

        if (Void.TYPE.isAssignableFrom(method.getReturnType())) {
          return null;
        } else if (method.getReturnType().isInterface()) {
          return Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
              new Class[] { method.getReturnType() }, 
              new MethodInvocationRecorder(false, invocations));
        } else {
          throw new AssertionError(String.format("Unsupported return type for %s: %s", 
              ImmediateModule.class.getSimpleName(), method.getReturnType().getName()));
        }
      }
    }
    
    private boolean isObjectToString(Method method) {
      return "toString".equals(method.getName()) 
             && method.getParameterTypes().length == 0;
    }

    private boolean isObjectHashCode(Method method) {
      return "hashCode".equals(method.getName()) 
             && method.getParameterTypes().length == 0;
    }

    private boolean isObjectEquals(Method method) {
      return "equals".equals(method.getName()) 
             && method.getParameterTypes().length == 1
             && method.getParameterTypes()[0].equals(Object.class);
    }

    @Override
    public boolean equals(Object that) {
      if (that == null) return false;
      if (Proxy.isProxyClass(that.getClass()))
        return super.equals(Proxy.getInvocationHandler(that));
      return false;
    }

    @Override
    public int hashCode() {
      return super.hashCode();
    }

    @Override
    public String toString() {
      return ImmediateModule.class.getSimpleName()+"'s implementation details";
    }
  }
  
  static class Invocation {
    Method method;
    Object[] args;
    boolean root;
    
    Object execute(Object target) {
      try {
        return method.invoke(target, args);
      } catch (IllegalArgumentException e) {
        throw new RuntimeException(e);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      } catch (InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
