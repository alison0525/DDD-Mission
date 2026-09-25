package com.back.global.exception;

import lombok.Getter;

@Getter
public class DomainException extends RuntimeException {
    private final String resulCode;
    private final String msg;

    public DomainException(String resulCode, String msg) {
        super(resulCode + " : " + msg);
        this.resulCode = resulCode;
        this.msg = msg;
    }
}
