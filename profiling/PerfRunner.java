package profiling;

import naive.Main;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class PerfRunner {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of("profile-output/naive.pid"),
                Long.toString(ProcessHandle.current().pid()));
        PrintStream original = System.out;
        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(sink);
            for (int i = 0; i < 20; i++) {
                Main.main(new String[0]);
            }
        } finally {
            System.setOut(original);
        }
    }
}
