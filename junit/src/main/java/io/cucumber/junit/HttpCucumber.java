package io.cucumber.junit;

import static io.cucumber.core.runtime.SynchronizedEventBus.synchronize;
import static io.cucumber.junit.FileNameCompatibleNames.uniqueSuffix;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.filter.Filters;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.logging.Logger;
import io.cucumber.core.logging.LoggerFactory;
import io.cucumber.core.options.Constants;
import io.cucumber.core.options.CucumberOptionsAnnotationParser;
import io.cucumber.core.options.CucumberProperties;
import io.cucumber.core.options.CucumberPropertiesParser;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.plugin.PluginFactory;
import io.cucumber.core.plugin.Plugins;
import io.cucumber.core.resource.ClassLoaders;
import io.cucumber.core.runtime.BackendServiceLoader;
import io.cucumber.core.runtime.BackendSupplier;
import io.cucumber.core.runtime.CucumberExecutionContext;
import io.cucumber.core.runtime.ExitStatus;
import io.cucumber.core.runtime.FeaturePathFeatureSupplier;
import io.cucumber.core.runtime.ObjectFactoryServiceLoader;
import io.cucumber.core.runtime.ObjectFactorySupplier;
import io.cucumber.core.runtime.ThreadLocalObjectFactorySupplier;
import io.cucumber.core.runtime.ThreadLocalRunnerSupplier;
import io.cucumber.core.runtime.TimeServiceEventBus;
import io.cucumber.junit.PickleRunners.PickleRunner;
import io.javalin.Javalin;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apiguardian.api.API;
import org.eclipse.jetty.util.StringUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerScheduler;
import org.junit.runners.model.Statement;

/**
 * Cucumber JUnit Runner.
 * <p>
 * A class annotated with {@code @RunWith(Cucumber.class)} will run feature
 * files as junit tests. In general, the runner class should be empty without
 * any fields or methods. For example: <blockquote>
 * 
 * <pre>
 * &#64;RunWith(Cucumber.class)
 * &#64;CucumberOptions(plugin = "pretty")
 * public class RunCucumberTest {
 * }
 * </pre>
 * 
 * </blockquote>
 * <p>
 * By default Cucumber will look for {@code .feature} and glue files on the
 * classpath, using the same resource path as the annotated class. For example,
 * if the annotated class is {@code com.example.RunCucumber} then features and
 * glue are assumed to be located in {@code com.example}.
 * <p>
 * Options can be provided in by (order of precedence):
 * <ol>
 * <li>Properties from {@link System#getProperties()} ()}</li>
 * <li>Properties from in {@link System#getenv()}</li>
 * <li>Annotating the runner class with {@link CucumberOptions}</li>
 * <li>Properties from {@value Constants#CUCUMBER_PROPERTIES_FILE_NAME}</li>
 * </ol>
 * For available properties see {@link Constants}.
 * <p>
 * Cucumber also supports JUnits {@link ClassRule}, {@link BeforeClass} and
 * {@link AfterClass} annotations. These will be executed before and after all
 * scenarios. Using these is not recommended as it limits the portability
 * between different runners; they may not execute correctly when using the
 * commandline, IntelliJ IDEA or Cucumber-Eclipse. Instead it is recommended to
 * use Cucumbers `Before` and `After` hooks.
 *
 * @see CucumberOptions
 */
@API(status = API.Status.STABLE)
public final class HttpCucumber extends ParentRunner<ParentRunner<?>> {

    private final Logger logger = LoggerFactory.getLogger(HttpCucumber.class);
    private final List<ParentRunner<?>> children;
    private final EventBus bus;
    private final Plugins plugins;
    private final CucumberExecutionContext context;

    private boolean multiThreadingAssumed = false;

    /**
     * Constructor called by JUnit.
     *
     * @param  clazz                                       the class with
     *                                                     the @RunWith
     *                                                     annotation.
     * @throws InitializationError if there is another
     *                                                     problem
     */
    public HttpCucumber(Class<?> clazz) throws InitializationError {
        super(clazz);
        Assertions.assertNoCucumberAnnotatedMethods(clazz);

        // Parse the options early to provide fast feedback about invalid
        // options
        RuntimeOptions propertiesFileOptions = new CucumberPropertiesParser()
                .parse(CucumberProperties.fromPropertiesFile())
                .build();

        RuntimeOptions annotationOptions = new CucumberOptionsAnnotationParser()
                .withOptionsProvider(new JUnitCucumberOptionsProvider())
                .parse(clazz)
                .build(propertiesFileOptions);

        RuntimeOptions environmentOptions = new CucumberPropertiesParser()
                .parse(CucumberProperties.fromEnvironment())
                .build(annotationOptions);

        RuntimeOptions runtimeOptions = new CucumberPropertiesParser()
                .parse(CucumberProperties.fromSystemProperties())
                .enablePublishPlugin()
                .build(environmentOptions);

        // Next parse the junit options
        JUnitOptions junitPropertiesFileOptions = new JUnitOptionsParser()
                .parse(CucumberProperties.fromPropertiesFile())
                .build();

        JUnitOptions junitAnnotationOptions = new JUnitOptionsParser()
                .parse(clazz)
                .build(junitPropertiesFileOptions);

        JUnitOptions junitEnvironmentOptions = new JUnitOptionsParser()
                .parse(CucumberProperties.fromEnvironment())
                .build(junitAnnotationOptions);

        JUnitOptions junitOptions = new JUnitOptionsParser()
                .parse(CucumberProperties.fromSystemProperties())
                .build(junitEnvironmentOptions);

        this.bus = synchronize(new TimeServiceEventBus(Clock.systemUTC(), UUID::randomUUID));

        // Parse the features early. Don't proceed when there are lexer errors
        FeatureParser parser = new FeatureParser(bus::generateId);
        Supplier<ClassLoader> classLoader = ClassLoaders::getDefaultClassLoader;
        FeaturePathFeatureSupplier featureSupplier = new FeaturePathFeatureSupplier(classLoader, runtimeOptions,
            parser);
        List<Feature> features = featureSupplier.get();

        // Create plugins after feature parsing to avoid the creation of empty
        // files on lexer errors.
        this.plugins = new Plugins(new PluginFactory(), runtimeOptions);
        ExitStatus exitStatus = new ExitStatus(runtimeOptions);
        this.plugins.addPlugin(exitStatus);

        ObjectFactoryServiceLoader objectFactoryServiceLoader = new ObjectFactoryServiceLoader(classLoader,
            runtimeOptions);
        ObjectFactorySupplier objectFactorySupplier = new ThreadLocalObjectFactorySupplier(objectFactoryServiceLoader);
        BackendSupplier backendSupplier = new BackendServiceLoader(clazz::getClassLoader, objectFactorySupplier);
        ThreadLocalRunnerSupplier runnerSupplier = new ThreadLocalRunnerSupplier(runtimeOptions, bus, backendSupplier,
            objectFactorySupplier);
        this.context = new CucumberExecutionContext(bus, exitStatus, runnerSupplier);
        Predicate<Pickle> filters = new Filters(runtimeOptions);

        Map<Optional<String>, List<Feature>> groupedByName = features.stream()
                .collect(groupingBy(Feature::getName));
        this.children = features.stream()
                .map(feature -> {
                    Integer uniqueSuffix = uniqueSuffix(groupedByName, feature, Feature::getName);
                    return FeatureRunner.create(feature, uniqueSuffix, filters, context, junitOptions);
                })
                .filter(runner -> !runner.isEmpty())
                .collect(toList());
    }

    @Override
    public void run(RunNotifier notifier) {
        logger.info(() -> "HttpCucumber is listening at localhost:9393");
        Javalin javalin = Javalin.create().start(9393);
        javalin.get("/api/v1/features", ctx -> {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();

            JsonObject root = new JsonObject();

            JsonArray features = new JsonArray();

            for (ParentRunner<?> child : children) {

                FeatureRunner fr = (FeatureRunner) child;

                JsonObject feature = new JsonObject();
                feature.addProperty("name", child.getDescription().getDisplayName());

                JsonArray scenarios = new JsonArray();
                for (PickleRunner frChild : fr.getChildren()) {
                    JsonObject scenario = new JsonObject();
                    scenario.addProperty("name", frChild.getDescription().getDisplayName());
                    scenarios.add(scenario);
                }
                feature.add("scenarios", scenarios);

                features.add(feature);
            }

            root.add("features", features);

            ctx.result(gson.toJson(root));
        });

        javalin.get("/api/v1/execute", ctx -> {
            String feature = ctx.queryParam("feature");

            if(feature == null || StringUtil.isBlank(feature)) {
                ctx.status(403);
                ctx.result("{\"message\": \"No feature provided\"}");
                return;
            }

            logger.info(() -> "Start to execute \"" + feature + "\"");

            List<ParentRunner<?>> runners = this.getChildren().stream()
                .filter(child -> child.getDescription().getDisplayName().equals(feature))
                .collect(Collectors.toList());

            if(runners.size() == 0) {
                ctx.status(404);
                ctx.result("{\"message\": \"Feature Not Found\"}");
                return;
            }

            ctx.result("{\"message\": \"running\"}");
        });

        while (true) {
            try {
            Thread.currentThread().sleep(10000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

    @Override
    protected List<ParentRunner<?>> getChildren() {
        for (ParentRunner<?> child : children) {

            FeatureRunner fr = (FeatureRunner) child;
            System.out.println(">>> " + child.getDescription().getDisplayName());

            for (PickleRunner frChild : fr.getChildren()) {
                System.out.println("       " + frChild.getDescription());
            }
        }
        return children;
    }

    @Override
    protected Description describeChild(ParentRunner<?> child) {
        return child.getDescription();
    }

    @Override
    protected void runChild(ParentRunner<?> child, RunNotifier notifier) {
        child.run(notifier);
    }

    @Override
    protected Statement childrenInvoker(RunNotifier notifier) {
        Statement statement = super.childrenInvoker(notifier);

        statement = new RunBeforeAllHooks(statement);
        statement = new RunAfterAllHooks(statement);

        statement = new StartTestRun(statement);
        statement = new FinishTestRun(statement);

        return statement;
    }

    @Override
    public void setScheduler(RunnerScheduler scheduler) {
        super.setScheduler(scheduler);
        multiThreadingAssumed = true;
    }

    private class StartTestRun extends Statement {
        private final Statement next;

        public StartTestRun(Statement next) {
            this.next = next;
        }

        @Override
        public void evaluate() throws Throwable {
            if (multiThreadingAssumed) {
                plugins.setSerialEventBusOnEventListenerPlugins(bus);
            } else {
                plugins.setEventBusOnEventListenerPlugins(bus);
            }
            context.startTestRun();
            next.evaluate();
        }

    }

    private class FinishTestRun extends Statement {
        private final Statement next;

        public FinishTestRun(Statement next) {
            this.next = next;
        }

        @Override
        public void evaluate() throws Throwable {
            try {
                next.evaluate();
            } finally {
                context.finishTestRun();
            }
        }

    }

    private class RunBeforeAllHooks extends Statement {
        private final Statement next;

        public RunBeforeAllHooks(Statement next) {
            this.next = next;
        }

        @Override
        public void evaluate() throws Throwable {
            context.runBeforeAllHooks();
            next.evaluate();
        }

    }

    private class RunAfterAllHooks extends Statement {
        private final Statement next;

        public RunAfterAllHooks(Statement next) {
            this.next = next;
        }

        @Override
        public void evaluate() throws Throwable {
            try {
                next.evaluate();
            } finally {
                context.runAfterAllHooks();
            }
        }

    }

}
