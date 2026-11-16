package dev.surge.booking.web;

import dev.surge.common.security.CurrentIdentity;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CurrentUser} to the signed-in user stated by the verified identity token. The chain already lets
 * only gateway users reach holds and orders; this is the second layer (401 missing-user for anyone else). No header
 * is ever read.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class) && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        CurrentUser annotation = parameter == null ? null : parameter.getParameterAnnotation(CurrentUser.class);
        return annotation != null && annotation.verifiedEmail() ? CurrentIdentity.requireVerifiedUser()
                : CurrentIdentity.requireUser();
    }
}
