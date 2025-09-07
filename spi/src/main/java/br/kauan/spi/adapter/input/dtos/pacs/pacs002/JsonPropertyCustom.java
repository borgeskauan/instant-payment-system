package br.kauan.spi.adapter.input.dtos.pacs.pacs002;

public @interface JsonPropertyCustom {
    String value();

    boolean required() default false;
}
