package com.bakedandbrowned.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * An absolute {@code https://} URL with a host and no user info. Null is valid (use
 * {@code @NotNull} to require a value). Rejects {@code http:}, {@code javascript:}, {@code data:} and
 * protocol-relative URLs, so a stored URL is safe to place in an {@code <img src>}.
 */
@Documented
@Constraint(validatedBy = HttpsUrl.Validator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE })
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpsUrl {

	String message() default "must be an https URL";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<HttpsUrl, String> {

		@Override
		public boolean isValid(String value, ConstraintValidatorContext context) {
			if (value == null) {
				return true;
			}
			try {
				URI uri = new URI(value);
				return uri.isAbsolute() && "https".equals(uri.getScheme().toLowerCase(Locale.ROOT))
						&& uri.getHost() != null && !uri.getHost().isBlank() && uri.getRawUserInfo() == null;
			}
			catch (URISyntaxException ex) {
				return false;
			}
		}

	}

}
