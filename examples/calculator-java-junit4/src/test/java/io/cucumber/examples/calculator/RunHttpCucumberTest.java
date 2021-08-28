package io.cucumber.examples.calculator;

import io.cucumber.junit.CucumberOptions;
import io.cucumber.junit.HttpCucumber;
import org.junit.runner.RunWith;

@RunWith(HttpCucumber.class)
@CucumberOptions(plugin = { "html:target/results.html", "message:target/results.ndjson" })
public class RunHttpCucumberTest {

}
