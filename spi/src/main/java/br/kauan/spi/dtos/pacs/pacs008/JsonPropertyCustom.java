package br.kauan.spi.dtos.pacs.pacs008;

public @interface JsonPropertyCustom {
    String value();

    boolean required() default false;
}
