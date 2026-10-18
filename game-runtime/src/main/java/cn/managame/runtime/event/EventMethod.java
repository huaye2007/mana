package cn.managame.runtime.event;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface EventMethod {
    int order() default 0;

    /**
     * The Domain whose state this listener owns. A listener runs on the publishing event's Route, so a
     * declared Domain must be the event's: listeners of one event type that declare different Domains fail
     * at build, and an event published to another Domain is rejected. 0 declares nothing and accepts any.
     */
    int domain() default 0;
}
