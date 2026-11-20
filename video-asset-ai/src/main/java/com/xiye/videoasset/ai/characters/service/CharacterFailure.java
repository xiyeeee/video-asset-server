package com.xiye.videoasset.ai.characters.service;

final class CharacterFailure extends RuntimeException {
    CharacterFailure(String code) {
        super(code);
    }

    String code() {
        return getMessage();
    }
}
