package br.kauan.dict.domain.services;

import java.util.InputMismatchException;

public class ValidadorCpfCnpj {

    /**
     * Valida um número de CPF
     *
     * @param cpf O CPF a ser validado (com ou sem formatação)
     * @return true se o CPF é válido, false caso contrário
     */
    public static boolean isCpfValido(String cpf) {
        // Remove caracteres não numéricos
        cpf = cpf.replaceAll("[^0-9]", "");

        // Verifica se tem 11 dígitos
        if (cpf.length() != 11) {
            return false;
        }

        // Verifica se todos os dígitos são iguais (CPF inválido)
        if (cpf.matches("(\\d)\\1{10}")) {
            return false;
        }

        try {
            // Calcula o primeiro dígito verificador
            int soma = 0;
            for (int i = 0; i < 9; i++) {
                int digito = Character.getNumericValue(cpf.charAt(i));
                soma += digito * (10 - i);
            }

            int resto = soma % 11;
            int digito1 = (resto < 2) ? 0 : 11 - resto;

            // Verifica o primeiro dígito verificador
            if (digito1 != Character.getNumericValue(cpf.charAt(9))) {
                return false;
            }

            // Calcula o segundo dígito verificador
            soma = 0;
            for (int i = 0; i < 10; i++) {
                int digito = Character.getNumericValue(cpf.charAt(i));
                soma += digito * (11 - i);
            }

            resto = soma % 11;
            int digito2 = (resto < 2) ? 0 : 11 - resto;

            // Verifica o segundo dígito verificador
            return digito2 == Character.getNumericValue(cpf.charAt(10));

        } catch (InputMismatchException e) {
            return false;
        }
    }

    /**
     * Valida um número de CNPJ
     *
     * @param cnpj O CNPJ a ser validado (com ou sem formatação)
     * @return true se o CNPJ é válido, false caso contrário
     */
    public static boolean isCnpjValido(String cnpj) {
        // Remove caracteres não numéricos
        cnpj = cnpj.replaceAll("[^0-9]", "");

        // Verifica se tem 14 dígitos
        if (cnpj.length() != 14) {
            return false;
        }

        // Verifica se todos os dígitos são iguais (CNPJ inválido)
        if (cnpj.matches("(\\d)\\1{13}")) {
            return false;
        }

        try {
            // Calcula o primeiro dígito verificador
            int soma = 0;
            int peso = 5;
            for (int i = 0; i < 12; i++) {
                int digito = Character.getNumericValue(cnpj.charAt(i));
                soma += digito * peso;
                peso = (peso == 2) ? 9 : peso - 1;
            }

            int resto = soma % 11;
            int digito1 = (resto < 2) ? 0 : 11 - resto;

            // Verifica o primeiro dígito verificador
            if (digito1 != Character.getNumericValue(cnpj.charAt(12))) {
                return false;
            }

            // Calcula o segundo dígito verificador
            soma = 0;
            peso = 6;
            for (int i = 0; i < 13; i++) {
                int digito = Character.getNumericValue(cnpj.charAt(i));
                soma += digito * peso;
                peso = (peso == 2) ? 9 : peso - 1;
            }

            resto = soma % 11;
            int digito2 = (resto < 2) ? 0 : 11 - resto;

            // Verifica o segundo dígito verificador
            return digito2 == Character.getNumericValue(cnpj.charAt(13));

        } catch (InputMismatchException e) {
            return false;
        }
    }
}