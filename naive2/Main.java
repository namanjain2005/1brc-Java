import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;


public  class Main {
    public static void main(String[] args) {
        long startTime = System.nanoTime();
        Map<String,CountryStat> aggregate = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader("weather_stations.csv"))){

            String line = null;
            //br.readLine();

            while ((line = br.readLine()) != null) {
                String[] values = line.split(";");
                String name = values[0];
                double val = Double.parseDouble(values[1]);
                if(aggregate.containsKey(name)){

                    CountryStat countryStat = aggregate.get(name);
                    countryStat.update(val);

                }else{
                    CountryStat countryStat = new CountryStat(val);
                    aggregate.put(name, countryStat);
                }
            }

            List<String> names = new ArrayList<>(aggregate.keySet());
            Collections.sort(names);
            for (String name : names) {
                CountryStat stat = aggregate.get(name);

                System.out.printf(
                    "%s;%s;%s;%s%n",
                    name,
                    roundToOneDecimal(stat.MinVal),
                    roundToOneDecimal(stat.Sum / stat.Count),
                    roundToOneDecimal(stat.MaxVal)
                );
            }
        } catch (IOException x) {
            System.err.format("IOException: %s%n", x);
        }
        double elapsedSeconds = (System.nanoTime() - startTime) / 1_000_000_000.0;
        System.err.printf("Time taken: %.6f seconds%n", elapsedSeconds);
     }

    private static String roundToOneDecimal(double value) {
        // Round the exact binary double to integer tenths using ties-to-even.
        long bits = Double.doubleToRawLongBits(value);
        boolean negative = bits < 0;
        int exponentBits = (int) ((bits >>> 52) & 0x7ffL);
        if (exponentBits == 0x7ff) {
            throw new IllegalArgumentException("Expected a finite temperature");
        }

        int exponent = exponentBits - 1023;
        int shift = 52 - exponent;
        long tenths;
        if (exponentBits == 0 || shift >= 58) {
            tenths = 0;
        } else if (shift <= 0) {
            throw new IllegalArgumentException("Temperature is outside the supported range");
        } else {
            long significand = (1L << 52) | (bits & ((1L << 52) - 1));
            long numerator = significand * 10L;
            long quotient = numerator >>> shift;
            long remainder = numerator - (quotient << shift);
            long halfway = 1L << (shift - 1);
            if (remainder > halfway || (remainder == halfway && (quotient & 1L) != 0)) {
                quotient++;
            }
            tenths = quotient;
        }

        long magnitude = tenths;
        return (negative ? "-" : "") + (magnitude / 10) + "." + (magnitude % 10);
    }
}
