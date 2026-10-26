package util;

public final class Temperature {
    private static final long UNITS_PER_DEGREE = 10_000;

    private Temperature() {}

    // Keep the decimal input exact using units of 1/10,000 degree.
    public static long parseScaled(String text) {
        int index = 0;
        boolean negative = false;
        if (text.charAt(index) == '-' || text.charAt(index) == '+') {
            negative = text.charAt(index) == '-';
            index++;
        }

        long whole = 0;
        int wholeDigits = 0;
        while (index < text.length() && text.charAt(index) != '.') {
            char digit = text.charAt(index++);
            if (digit < '0' || digit > '9') {
                throw new NumberFormatException("Invalid temperature: " + text);
            }
            whole = whole * 10 + (digit - '0');
            wholeDigits++;
        }
        if (wholeDigits == 0) {
            throw new NumberFormatException("Invalid temperature: " + text);
        }

        long fraction = 0;
        int fractionalDigits = 0;
        if (index < text.length()) {
            index++;
            if (index == text.length()) {
                throw new NumberFormatException("Invalid temperature: " + text);
            }
            while (index < text.length()) {
                char digit = text.charAt(index++);
                if (digit < '0' || digit > '9' || fractionalDigits == 4) {
                    throw new NumberFormatException("Invalid temperature: " + text);
                }
                fraction = fraction * 10 + (digit - '0');
                fractionalDigits++;
            }
        }
        while (fractionalDigits++ < 4) {
            fraction *= 10;
        }

        long scaled = whole * UNITS_PER_DEGREE + fraction;
        return negative ? -scaled : scaled;
    }

    public static String formatValue(long scaled) {
        return formatRatioAsBinary64(scaled, UNITS_PER_DEGREE);
    }

    public static String formatAverage(long sum, long count) {
        return formatRatioAsBinary64(sum, count * UNITS_PER_DEGREE);
    }

    // Match the expected output's binary64 rounding without using floating-point
    // arithmetic: first encode the exact rational as its nearest IEEE-754 value,
    // then round that value to tenths using integer operations.
    private static String formatRatioAsBinary64(long numerator, long denominator) {
        return formatBinary64ToTenths(ratioToBinary64Bits(numerator, denominator));
    }

    private static long ratioToBinary64Bits(long numerator, long denominator) {
        boolean negative = numerator < 0;
        long magnitude = Math.abs(numerator);
        if (magnitude == 0) {
            return 0;
        }

        int numeratorBit = 63 - Long.numberOfLeadingZeros(magnitude);
        int denominatorBit = 63 - Long.numberOfLeadingZeros(denominator);
        int exponent = numeratorBit - denominatorBit;
        if (exponent >= 0) {
            if (magnitude < (denominator << exponent)) {
                exponent--;
            }
        } else if ((magnitude << -exponent) < denominator) {
            exponent--;
        }

        int shift = 52 - exponent;
        long quotient = magnitude / denominator;
        long remainder = magnitude % denominator;
        long significand = quotient << shift;
        for (int bit = shift - 1; bit >= 0; bit--) {
            remainder <<= 1;
            if (remainder >= denominator) {
                remainder -= denominator;
                if (bit >= Long.SIZE - 1) {
                    throw new ArithmeticException("Temperature is outside the supported range");
                }
                significand |= 1L << bit;
            }
        }

        long complement = denominator - remainder;
        if (remainder > complement || (remainder == complement && (significand & 1L) != 0)) {
            significand++;
        }
        if (significand == (1L << 53)) {
            significand >>>= 1;
            exponent++;
        }

        long sign = negative ? Long.MIN_VALUE : 0;
        long biasedExponent = (long) (exponent + 1023) << 52;
        long fraction = significand - (1L << 52);
        return sign | biasedExponent | fraction;
    }

    private static String formatBinary64ToTenths(long bits) {
        boolean negative = bits < 0;
        int exponentBits = (int) ((bits >>> 52) & 0x7ffL);
        if (exponentBits == 0) {
            return formatTenths(0, negative);
        }

        int exponent = exponentBits - 1023;
        if (exponent < -5) {
            return formatTenths(0, negative);
        }

        int shift = 52 - exponent;
        long significand = (1L << 52) | (bits & ((1L << 52) - 1));
        long scaledNumerator = significand * 10L;
        long quotient = scaledNumerator >>> shift;
        long remainder = scaledNumerator - (quotient << shift);
        long halfway = 1L << (shift - 1);
        if (remainder > halfway || (remainder == halfway && (quotient & 1L) != 0)) {
            quotient++;
        }
        return formatTenths(negative ? -quotient : quotient, negative);
    }

    private static String formatTenths(long tenths, boolean negativeZero) {
        boolean negative = tenths < 0 || (tenths == 0 && negativeZero);
        long magnitude = Math.abs(tenths);
        return (negative ? "-" : "") + (magnitude / 10) + "." + (magnitude % 10);
    }
}
