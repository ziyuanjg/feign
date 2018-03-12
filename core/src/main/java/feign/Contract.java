/**
 * Copyright 2012-2018 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * Defines what annotations and values are valid on interfaces.
 */
public interface Contract {

  /**
   * Called to parse the methods in the class that are linked to HTTP requests.
   *
   * @param targetType {@link feign.Target#type() type} of the Feign interface.
   */
  // TODO: break this and correct spelling at some point
  List<MethodMetadata> parseAndValidatateMetadata(Class<?> targetType);

  abstract class BaseContract implements Contract {

    @Override
    public List<MethodMetadata> parseAndValidatateMetadata(Class<?> targetType) {
      //不支持在类上声明泛型
      checkState(targetType.getTypeParameters().length == 0, "Parameterized types unsupported: %s",
                 targetType.getSimpleName());
      //TODO 仅支持单继承，此处可能是为了规避Client使用的是类，但是后面执行的时候又不允许非接口执行，比较奇怪
      checkState(targetType.getInterfaces().length <= 1, "Only single inheritance supported: %s",
                 targetType.getSimpleName());
      //仅支持单级继承
      if (targetType.getInterfaces().length == 1) {
        checkState(targetType.getInterfaces()[0].getInterfaces().length == 0,
                   "Only single-level inheritance supported: %s",
                   targetType.getSimpleName());
      }
      Map<String, MethodMetadata> result = new LinkedHashMap<String, MethodMetadata>();
      for (Method method : targetType.getMethods()) {
        //静态方法和default方法不处理
        if (method.getDeclaringClass() == Object.class ||
            (method.getModifiers() & Modifier.STATIC) != 0 ||
            Util.isDefault(method)) {
          continue;
        }
        MethodMetadata metadata = parseAndValidateMetadata(targetType, method);
        checkState(!result.containsKey(metadata.configKey()), "Overrides unsupported: %s",
                   metadata.configKey());
        result.put(metadata.configKey(), metadata);
      }
      return new ArrayList<MethodMetadata>(result.values());
    }

    /**
     * @deprecated use {@link #parseAndValidateMetadata(Class, Method)} instead.
     */
    @Deprecated
    public MethodMetadata parseAndValidatateMetadata(Method method) {
      return parseAndValidateMetadata(method.getDeclaringClass(), method);
    }

    /**
     * Called indirectly by {@link #parseAndValidatateMetadata(Class)}.
     */
    protected MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
      MethodMetadata data = new MethodMetadata();
      data.returnType(Types.resolve(targetType, targetType, method.getGenericReturnType()));
      data.configKey(Feign.configKey(targetType, method));

      // 解析类注解
      if(targetType.getInterfaces().length == 1) {
        processAnnotationOnClass(data, targetType.getInterfaces()[0]);
      }
      processAnnotationOnClass(data, targetType);

      // 解析方法注解
      for (Annotation methodAnnotation : method.getAnnotations()) {
        processAnnotationOnMethod(data, methodAnnotation, method);
      }
      checkState(data.template().method() != null,
                 "Method %s not annotated with HTTP method type (ex. GET, POST)",
                 method.getName());
      Class<?>[] parameterTypes = method.getParameterTypes();
      Type[] genericParameterTypes = method.getGenericParameterTypes();

      // 解析参数注解
      Annotation[][] parameterAnnotations = method.getParameterAnnotations();
      int count = parameterAnnotations.length;
      for (int i = 0; i < count; i++) {
        boolean isHttpAnnotation = false;
        if (parameterAnnotations[i] != null) {
          isHttpAnnotation = processAnnotationsOnParameter(data, parameterAnnotations[i], i);
        }
        if (parameterTypes[i] == URI.class) {
          data.urlIndex(i);
        } else if (!isHttpAnnotation) {
          checkState(data.formParams().isEmpty(),
                     "Body parameters cannot be used with form parameters.");
          checkState(data.bodyIndex() == null, "Method has too many Body parameters: %s", method);
          data.bodyIndex(i);
          data.bodyType(Types.resolve(targetType, targetType, genericParameterTypes[i]));
        }
      }

      if (data.headerMapIndex() != null) {
        checkMapString("HeaderMap", parameterTypes[data.headerMapIndex()], genericParameterTypes[data.headerMapIndex()]);
      }

      if (data.queryMapIndex() != null) {
        checkMapString("QueryMap", parameterTypes[data.queryMapIndex()], genericParameterTypes[data.queryMapIndex()]);
      }

      return data;
    }

    private static void checkMapString(String name, Class<?> type, Type genericType) {
      checkState(Map.class.isAssignableFrom(type),
              "%s parameter must be a Map: %s", name, type);
      Type[] parameterTypes = ((ParameterizedType) genericType).getActualTypeArguments();
      Class<?> keyClass = (Class<?>) parameterTypes[0];
      checkState(String.class.equals(keyClass),
              "%s key must be a String: %s", name, keyClass.getSimpleName());
    }

    /**
     * Called by parseAndValidateMetadata twice, first on the declaring class, then on the
     * target type (unless they are the same).
     *
     * @param data       metadata collected so far relating to the current java method.
     * @param clz        the class to process
     */
    protected abstract void processAnnotationOnClass(MethodMetadata data, Class<?> clz);

    /**
     * @param data       metadata collected so far relating to the current java method.
     * @param annotation annotations present on the current method annotation.
     * @param method     method currently being processed.
     */
    protected abstract void processAnnotationOnMethod(MethodMetadata data, Annotation annotation,
                                                      Method method);

    /**
     * @param data        metadata collected so far relating to the current java method.
     * @param annotations annotations present on the current parameter annotation.
     * @param paramIndex  if you find a name in {@code annotations}, call {@link
     *                    #nameParam(MethodMetadata, String, int)} with this as the last parameter.
     * @return true if you called {@link #nameParam(MethodMetadata, String, int)} after finding an
     * http-relevant annotation.
     */
    protected abstract boolean processAnnotationsOnParameter(MethodMetadata data,
                                                             Annotation[] annotations,
                                                             int paramIndex);

    /**
     * @deprecated dead-code will remove in feign 10
     */
    @Deprecated
    // deprecated as only used in a sub-type
    protected Collection<String> addTemplatedParam(Collection<String> possiblyNull, String name) {
      if (possiblyNull == null) {
        possiblyNull = new ArrayList<String>();
      }
      possiblyNull.add(String.format("{%s}", name));
      return possiblyNull;
    }

    /**
     * links a parameter name to its index in the method signature.
     */
    protected void nameParam(MethodMetadata data, String name, int i) {
      Collection<String>
          names =
          data.indexToName().containsKey(i) ? data.indexToName().get(i) : new ArrayList<String>();
      names.add(name);
      data.indexToName().put(i, names);
    }
  }

  class Default extends BaseContract {

    /**
     * 解析类上的Headers注解
     * @param data       metadata collected so far relating to the current java method.
     * @param targetType
     */
    @Override
    protected void processAnnotationOnClass(MethodMetadata data, Class<?> targetType) {
      if (targetType.isAnnotationPresent(Headers.class)) {
        String[] headersOnType = targetType.getAnnotation(Headers.class).value();
        checkState(headersOnType.length > 0, "Headers annotation was empty on type %s.",
                   targetType.getName());
        Map<String, Collection<String>> headers = toMap(headersOnType);
        headers.putAll(data.template().headers());
        data.template().headers(null); // to clear
        data.template().headers(headers);
      }
    }

    /**
     * 解析方法上的RequestLine、Body、Headers注解
     * @param data       metadata collected so far relating to the current java method.
     * @param methodAnnotation
     * @param method     method currently being processed.
     */
    @Override
    protected void processAnnotationOnMethod(MethodMetadata data, Annotation methodAnnotation,
                                             Method method) {
      Class<? extends Annotation> annotationType = methodAnnotation.annotationType();
      if (annotationType == RequestLine.class) {
        String requestLine = RequestLine.class.cast(methodAnnotation).value();
        checkState(emptyToNull(requestLine) != null,
                   "RequestLine annotation was empty on method %s.", method.getName());
        //TODO 单写一个请求类型可以通过，但是不会发起请求
        if (requestLine.indexOf(' ') == -1) {
          checkState(requestLine.indexOf('/') == -1,
              "RequestLine annotation didn't start with an HTTP verb on method %s.",
              method.getName());
          data.template().method(requestLine);
          return;
        }
        // 截取http请求类型
        data.template().method(requestLine.substring(0, requestLine.indexOf(' ')));
        if (requestLine.indexOf(' ') == requestLine.lastIndexOf(' ')) {
          // no HTTP version is ok
          data.template().append(requestLine.substring(requestLine.indexOf(' ') + 1));
        } else {
          // skip HTTP version 此处有一个需要注意的地方，如果手误在GET后面多打了空格，会导致请求不到接口！
          data.template().append(
              requestLine.substring(requestLine.indexOf(' ') + 1, requestLine.lastIndexOf(' ')));
        }
        // 设置符号转义开关
        data.template().decodeSlash(RequestLine.class.cast(methodAnnotation).decodeSlash());

      } else if (annotationType == Body.class) {
        String body = Body.class.cast(methodAnnotation).value();
        checkState(emptyToNull(body) != null, "Body annotation was empty on method %s.",
                   method.getName());
        if (body.indexOf('{') == -1) {
          data.template().body(body);
        } else {
          data.template().bodyTemplate(body);
        }
      } else if (annotationType == Headers.class) {
        String[] headersOnMethod = Headers.class.cast(methodAnnotation).value();
        checkState(headersOnMethod.length > 0, "Headers annotation was empty on method %s.",
                   method.getName());
        data.template().headers(toMap(headersOnMethod));
      }
    }

    /**
     * 解析参数上的Param、QueryMap、HeaderMap注解
     * @param data        metadata collected so far relating to the current java method.
     * @param annotations annotations present on the current parameter annotation.
     * @param paramIndex  if you find a name in {@code annotations}, call {@link
     *                    #nameParam(MethodMetadata, String, int)} with this as the last parameter.
     * @return
     */
    @Override
    protected boolean processAnnotationsOnParameter(MethodMetadata data, Annotation[] annotations,
                                                    int paramIndex) {
      boolean isHttpAnnotation = false;
      for (Annotation annotation : annotations) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        if (annotationType == Param.class) {
          Param paramAnnotation = (Param) annotation;
          String name = paramAnnotation.value();
          checkState(emptyToNull(name) != null, "Param annotation was empty on param %s.", paramIndex);
          // 绑定形参别名
          nameParam(data, name, paramIndex);
          Class<? extends Param.Expander> expander = paramAnnotation.expander();
          if (expander != Param.ToStringExpander.class) {
            data.indexToExpanderClass().put(paramIndex, expander);
          }
          data.indexToEncoded().put(paramIndex, paramAnnotation.encoded());
          isHttpAnnotation = true;
          String varName = '{' + name + '}';
          if (!data.template().url().contains(varName) &&
              !searchMapValuesContainsSubstring(data.template().queries(), varName) &&
              !searchMapValuesContainsSubstring(data.template().headers(), varName)) {
            data.formParams().add(name);
          }
        } else if (annotationType == QueryMap.class) {
          checkState(data.queryMapIndex() == null, "QueryMap annotation was present on multiple parameters.");
          // TODO 此处貌似仅支持一个QueryMap注解，如果有多个不知道会不会覆盖，待测
          data.queryMapIndex(paramIndex);
          data.queryMapEncoded(QueryMap.class.cast(annotation).encoded());
          isHttpAnnotation = true;
        } else if (annotationType == HeaderMap.class) {
          checkState(data.headerMapIndex() == null, "HeaderMap annotation was present on multiple parameters.");
          data.headerMapIndex(paramIndex);
          isHttpAnnotation = true;
        }
      }
      return isHttpAnnotation;
    }

    private static <K, V> boolean searchMapValuesContainsSubstring(Map<K, Collection<String>> map,
                                                                   String search) {
      Collection<Collection<String>> values = map.values();
      if (values == null) {
        return false;
      }

      for (Collection<String> entry : values) {
        for (String value : entry) {
          if (value.contains(search)) {
            return true;
          }
        }
      }

      return false;
    }

    private static Map<String, Collection<String>> toMap(String[] input) {
      Map<String, Collection<String>>
          result =
          new LinkedHashMap<String, Collection<String>>(input.length);
      for (String header : input) {
        int colon = header.indexOf(':');
        String name = header.substring(0, colon);
        if (!result.containsKey(name)) {
          result.put(name, new ArrayList<String>(1));
        }
        result.get(name).add(header.substring(colon + 2));
      }
      return result;
    }
  }
}
