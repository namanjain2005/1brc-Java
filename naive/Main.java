import java.util.TreeMap;
import java.util.Map;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public  class Main {
    public static void main(String[] args) {
        long startTime = System.nanoTime();
        Map<String,CountryStat> aggregate = new TreeMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader("weather_stations.csv"))){

            String line = null;


            while ((line = br.readLine()) != null) {
                String[] values = line.split(";");
                String name = values[0];
                long val = Temperature.parseScaled(values[1]);
                CountryStat countryStat = aggregate.get(name);
                if(countryStat != null){

                    countryStat.update(val);

                }else{
                    aggregate.put(name,new CountryStat(val));
                }
            }

            for (Map.Entry<String, CountryStat> entry : aggregate.entrySet()) {
                String name = entry.getKey();
                CountryStat stat = entry.getValue();

                System.out.printf(
                    "%s;%s;%s;%s%n",
                    name,
                    Temperature.formatValue(stat.MinVal),
                    Temperature.formatAverage(stat.Sum, stat.Count),
                    Temperature.formatValue(stat.MaxVal)
                );
            }
        } catch (IOException x) {
            System.err.format("IOException: %s%n", x);
        }
        long elapsedNanos = System.nanoTime() - startTime;
        System.err.printf("Time taken: %d.%06d seconds%n",
                elapsedNanos / 1_000_000_000L,
                (elapsedNanos % 1_000_000_000L) / 1_000L);
     }

}
