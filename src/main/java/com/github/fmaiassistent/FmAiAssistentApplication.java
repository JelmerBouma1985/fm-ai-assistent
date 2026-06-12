package com.github.fmaiassistent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;

import java.util.Arrays;

@SpringBootApplication
public class FmAiAssistentApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FmAiAssistentApplication.class);
        if (Arrays.stream(args).anyMatch(arg -> arg.equals("--export-club") || arg.startsWith("--export-club="))) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        app.run(args);
    }
}
