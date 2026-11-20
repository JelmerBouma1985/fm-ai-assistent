package com.github.fmaiassistent.tools;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/** Companion-app effects only. Football Manager memory and saves remain read-only. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolInfo {
    Category category();
    Effect effect() default Effect.READ_ONLY;
    boolean snapshot() default true;
    boolean tactic() default false;
    boolean managedClub() default false;
    boolean career() default false;
    enum Category { SCOUTING, TACTICS, SQUAD, STAFF, CLUBS, RECRUITMENT, UTILITIES, EXPORT, OTHER }
    enum Effect {
        READ_ONLY("Reads FM data"), APP_LOCAL_CHANGE("Updates application data"),
        LOCAL_FILE_EXPORT("Creates a local export"), UNKNOWN("Unclassified effect");
        private final String label;
        Effect(String label) { this.label = label; }
        public String label() { return label; }
    }
}
