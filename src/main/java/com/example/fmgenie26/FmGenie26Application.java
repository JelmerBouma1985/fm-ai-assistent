package com.example.fmgenie26;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;

import java.util.Arrays;

@SpringBootApplication
public class FmGenie26Application {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FmGenie26Application.class);
        if (Arrays.stream(args).anyMatch(arg -> arg.equals("--export-club") || arg.startsWith("--export-club="))) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        app.run(args);
    }
}
