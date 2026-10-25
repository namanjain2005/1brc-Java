import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;


public  class Main {
    public static void main(String[] args) {
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
     }

    private static String roundToOneDecimal(double value) {
        BigDecimal rounded = new BigDecimal(value)
            .setScale(1, RoundingMode.HALF_EVEN);
        if (rounded.signum() == 0 && Double.doubleToRawLongBits(value) < 0) {
            return "-0.0";
        }
        return rounded.toPlainString();
    }
}
