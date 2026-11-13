package dev.surge.booking.web;

import dev.surge.booking.error.MissingUserException;
import java.util.regex.Pattern;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-User-Id";
    private static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class) && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String userId = webRequest.getHeader(HEADER);
        if (userId == null || !USER_ID.matcher(userId).matches()) {
            throw new MissingUserException();
        }
        return userId;
    }
}
