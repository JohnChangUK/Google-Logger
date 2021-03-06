import com.google.inject.Guice;
import com.google.inject.Injector;
import modules.LoggingModule;
import modules.PropertiesModule;

import java.util.concurrent.ExecutionException;

public class DependencyInjectionLoggerMain {
    public static void main(String[] args) {
        Injector injector = Guice.createInjector(new PropertiesModule(), new LoggingModule());
        final TaskManager taskManager = injector.getInstance(TaskManager.class);
        try {
            taskManager.execute().get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
    }
}
