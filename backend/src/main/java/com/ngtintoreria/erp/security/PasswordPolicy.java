package com.ngtintoreria.erp.security;

public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static void validate(String password) {
        if (password == null || password.length() < 10)
            throw new IllegalArgumentException("La contraseña debe tener al menos 10 caracteres");
        if (password.chars().noneMatch(Character::isUpperCase))
            throw new IllegalArgumentException("La contraseña debe incluir al menos una mayúscula");
        if (password.chars().noneMatch(Character::isLowerCase))
            throw new IllegalArgumentException("La contraseña debe incluir al menos una minúscula");
        if (password.chars().noneMatch(Character::isDigit))
            throw new IllegalArgumentException("La contraseña debe incluir al menos un número");
        if (password.chars().allMatch(Character::isLetterOrDigit))
            throw new IllegalArgumentException("La contraseña debe incluir al menos un símbolo");
    }
}
