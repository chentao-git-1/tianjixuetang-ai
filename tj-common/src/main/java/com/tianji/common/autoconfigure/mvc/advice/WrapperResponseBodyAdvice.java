package com.tianji.common.autoconfigure.mvc.advice;

import com.tianji.common.annotations.NoWrapper;
import com.tianji.common.constants.Constant;
import com.tianji.common.domain.R;
import com.tianji.common.utils.WebUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
@ConditionalOnClass({ResponseBodyAdvice.class})
@RequiredArgsConstructor
public class WrapperResponseBodyAdvice implements ResponseBodyAdvice<Object> {
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(MethodParameter methodParameter, @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        if(methodParameter.hasMethodAnnotation(NoWrapper.class)){
            // 如果方法上标注了NoWrapper注解，则不进行包装
            return false;
        }
        return methodParameter.getParameterType() != R.class && WebUtils.isGatewayRequest();
    }

    @Override
    public Object beforeBodyWrite(
            Object body, @NonNull MethodParameter returnType, @NonNull MediaType selectedContentType,
            @NonNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
            @NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response) {
        if (request.getURI().getPath().equals("/v2/api-docs")
                || MediaType.TEXT_EVENT_STREAM.isCompatibleWith(selectedContentType)
                || "true".equals(response.getHeaders().getFirst(Constant.BODY_PROCESSED_MARK_HEADER))) {
            return body;
        }
        // 例如框架返回406空响应时，不能将它包装成成功结果。
        if (body == null && WebUtils.getResponse() != null && WebUtils.getResponse().getStatus() >= 400) {
            return null;
        }
        Object result = body instanceof R ? body : (body == null ? R.ok() : R.ok(body))
                .requestId(MDC.get(Constant.REQUEST_ID_HEADER));
        // Spring已选择字符串转换器时必须仍返回String，保留统一JSON响应结构。
        if (StringHttpMessageConverter.class.isAssignableFrom(selectedConverterType)) {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            try {
                return objectMapper.writeValueAsString(result);
            } catch (JsonProcessingException error) {
                throw new HttpMessageNotWritableException("统一响应序列化失败", error);
            }
        }
        return result;
    }
}
