package br.kauan.spi.dtos.pacs.pacs002;

public @interface JsonPropertyCustom {
    String value();

    boolean required() default false;
}
