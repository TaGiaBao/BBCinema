package com.bbcinema.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller action of the internal area that goes into the audit log. The value is the action as the
 * general manager reads it on /admin/audit, e.g. "Xoá phim". {@link AuditInterceptor} does the recording.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
    String value();
}
