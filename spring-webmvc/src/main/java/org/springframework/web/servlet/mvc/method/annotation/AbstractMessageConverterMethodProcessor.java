/*
 * Copyright 2002-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.mvc.method.annotation;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.util.CollectionUtils;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

/**
 * Extends {@link AbstractMessageConverterMethodArgumentResolver} with the ability to handle
 * method return values by writing to the response with {@link HttpMessageConverter}s.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @since 3.1
 */
public abstract class AbstractMessageConverterMethodProcessor extends AbstractMessageConverterMethodArgumentResolver
		implements HandlerMethodReturnValueHandler {

	private static final MediaType MEDIA_TYPE_APPLICATION = new MediaType("application");

	private final ContentNegotiationManager contentNegotiationManager;

	private final ResponseBodyAdviceChain adviceChain;


	protected AbstractMessageConverterMethodProcessor(List<HttpMessageConverter<?>> messageConverters) {
		this(messageConverters, null);
	}

	protected AbstractMessageConverterMethodProcessor(List<HttpMessageConverter<?>> messageConverters,
			ContentNegotiationManager manager) {
		this(messageConverters, manager, null);
	}

	protected AbstractMessageConverterMethodProcessor(List<HttpMessageConverter<?>> messageConverters,
			ContentNegotiationManager manager, List<Object> responseBodyAdvice) {

		super(messageConverters);
		this.contentNegotiationManager = (manager != null ? manager : new ContentNegotiationManager());
		this.adviceChain = new ResponseBodyAdviceChain(responseBodyAdvice);
	}


	protected ResponseBodyAdviceChain getAdviceChain() {
		return this.adviceChain;
	}

	/**
	 * Creates a new {@link HttpOutputMessage} from the given {@link NativeWebRequest}.
	 * @param webRequest the web request to create an output message from
	 * @return the output message
	 */
	protected ServletServerHttpResponse createOutputMessage(NativeWebRequest webRequest) {
		HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);
		return new ServletServerHttpResponse(response);
	}

	/**
	 * Writes the given return value to the given web request. Delegates to
	 * {@link #writeWithMessageConverters(Object, MethodParameter, ServletServerHttpRequest, ServletServerHttpResponse)}
	 */
	protected <T> void writeWithMessageConverters(T returnValue, MethodParameter returnType, NativeWebRequest webRequest)
			throws IOException, HttpMediaTypeNotAcceptableException {

		ServletServerHttpRequest inputMessage = createInputMessage(webRequest);
		ServletServerHttpResponse outputMessage = createOutputMessage(webRequest);
		writeWithMessageConverters(returnValue, returnType, inputMessage, outputMessage);
	}

	/**
	 * Writes the given return type to the given output message.
	 * @param returnValue the value to write to the output message
	 * @param returnType the type of the value
	 * @param inputMessage the input messages. Used to inspect the {@code Accept} header.
	 * @param outputMessage the output message to write to
	 * @throws IOException thrown in case of I/O errors
	 * @throws HttpMediaTypeNotAcceptableException thrown when the conditions indicated by {@code Accept} header on
	 * the request cannot be met by the message converters
	 */
	@SuppressWarnings("unchecked")
	protected <T> void writeWithMessageConverters(T returnValue, MethodParameter returnType,
			ServletServerHttpRequest inputMessage, ServletServerHttpResponse outputMessage)
			throws IOException, HttpMediaTypeNotAcceptableException {

		// 获取返回值的类型
		Class<?> returnValueClass = getReturnValueType(returnValue, returnType);
		HttpServletRequest servletRequest = inputMessage.getServletRequest();
		// 获取request请求头的Accept头
		List<MediaType> requestedMediaTypes = getAcceptableMediaTypes(servletRequest);
		// 获取response头中的content-type
		List<MediaType> producibleMediaTypes = getProducibleMediaTypes(servletRequest, returnValueClass);

		Set<MediaType> compatibleMediaTypes = new LinkedHashSet<MediaType>();
		// 将Accept头和content-type一一对应，找出能够匹配的content-type
		for (MediaType requestedType : requestedMediaTypes) {
			for (MediaType producibleType : producibleMediaTypes) {
				if (requestedType.isCompatibleWith(producibleType)) {
					compatibleMediaTypes.add(getMostSpecificMediaType(requestedType, producibleType));
				}
			}
		}
		if (compatibleMediaTypes.isEmpty()) {
			if (returnValue != null) {
				throw new HttpMediaTypeNotAcceptableException(producibleMediaTypes);
			}
			return;
		}

		List<MediaType> mediaTypes = new ArrayList<MediaType>(compatibleMediaTypes);
		// 不同的content-type有不同的优先级，按照优先级进行排序
		MediaType.sortBySpecificityAndQuality(mediaTypes);

		MediaType selectedMediaType = null;
		for (MediaType mediaType : mediaTypes) {
			// 找出实际的类型
			if (mediaType.isConcrete()) {
				selectedMediaType = mediaType;
				break;
			}
			else if (mediaType.equals(MediaType.ALL) || mediaType.equals(MEDIA_TYPE_APPLICATION)) {
				selectedMediaType = MediaType.APPLICATION_OCTET_STREAM;
				break;
			}
		}

		if (selectedMediaType != null) {
			selectedMediaType = selectedMediaType.removeQualityValue();
			// 查看哪个converter能够处理对应的返回值
			for (HttpMessageConverter<?> messageConverter : this.messageConverters) {
				if (messageConverter.canWrite(returnValueClass, selectedMediaType)) {
					// 使用advice链对结果进行处理, 默认最后是RequestBodyAdvice和ResponseBodyAdvice
					returnValue = this.adviceChain.invoke(returnValue, returnType, selectedMediaType,
							(Class<HttpMessageConverter<?>>) messageConverter.getClass(), inputMessage, outputMessage);
					if (returnValue != null) {
						((HttpMessageConverter<T>) messageConverter).write(returnValue, selectedMediaType, outputMessage);
						if (logger.isDebugEnabled()) {
							logger.debug("Written [" + returnValue + "] as \"" + selectedMediaType + "\" using [" +
									messageConverter + "]");
						}
					}
					return;
				}
			}
		}

		if (returnValue != null) {
			throw new HttpMediaTypeNotAcceptableException(this.allSupportedMediaTypes);
		}
	}

	/**
	 * Return the type of the value to be written to the response. Typically this
	 * is a simple check via getClass on the returnValue but if the returnValue is
	 * null, then the returnType needs to be examined possibly including generic
	 * type determination (e.g. {@code ResponseEntity<T>}).
	 */
	protected Class<?> getReturnValueType(Object returnValue, MethodParameter returnType) {
		return (returnValue != null ? returnValue.getClass() : returnType.getParameterType());
	}

	/**
	 * Returns the media types that can be produced:
	 * <ul>
	 * <li>The producible media types specified in the request mappings, or
	 * <li>Media types of configured converters that can write the specific return value, or
	 * <li>{@link MediaType#ALL}
	 * </ul>
	 */
	@SuppressWarnings("unchecked")
	protected List<MediaType> getProducibleMediaTypes(HttpServletRequest request, Class<?> returnValueClass) {
		// 查看是否有指定response返回的类型，例如试用注解指定produceType等
		Set<MediaType> mediaTypes = (Set<MediaType>) request.getAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
		if (!CollectionUtils.isEmpty(mediaTypes)) {
			return new ArrayList<MediaType>(mediaTypes);
		}
		else if (!this.allSupportedMediaTypes.isEmpty()) {
			// 如果没有，则判断哪些convert能够转换对应的值，然后获取对应的converter能够转换的类型
			List<MediaType> result = new ArrayList<MediaType>();
			for (HttpMessageConverter<?> converter : this.messageConverters) {
				if (converter.canWrite(returnValueClass, null)) {
					result.addAll(converter.getSupportedMediaTypes());
				}
			}
			return result;
		}
		else {
			return Collections.singletonList(MediaType.ALL);
		}
	}

	private List<MediaType> getAcceptableMediaTypes(HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
		List<MediaType> mediaTypes = this.contentNegotiationManager.resolveMediaTypes(new ServletWebRequest(request));
		return (mediaTypes.isEmpty() ? Collections.singletonList(MediaType.ALL) : mediaTypes);
	}

	/**
	 * Return the more specific of the acceptable and the producible media types
	 * with the q-value of the former.
	 */
	private MediaType getMostSpecificMediaType(MediaType acceptType, MediaType produceType) {
		MediaType produceTypeToUse = produceType.copyQualityValue(acceptType);
		return (MediaType.SPECIFICITY_COMPARATOR.compare(acceptType, produceTypeToUse) <= 0 ? acceptType : produceTypeToUse);
	}

}
