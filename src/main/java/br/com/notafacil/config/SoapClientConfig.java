package br.com.notafacil.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.transport.http.HttpComponentsMessageSender; // Using HttpComponents for more control

import java.time.Duration;

@Configuration
public class SoapClientConfig {

    private static final Logger log = LoggerFactory.getLogger(SoapClientConfig.class);

    @Value("${nfse.service.connection-timeout:10000}") // Default 10 seconds
    private int connectionTimeoutMillis;

    @Value("${nfse.service.read-timeout:60000}") // Default 60 seconds
    private int readTimeoutMillis;

    @Bean
    public Jaxb2Marshaller marshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        // Scan for JAXB annotated classes in the specified package
        marshaller.setPackagesToScan("br.com.notafacil.schemas");
        log.info("JAXB Marshaller configured to scan package: br.com.notafacil.schemas");
        return marshaller;
    }

    @Bean
    public HttpComponentsMessageSender httpComponentsMessageSender() {
        HttpComponentsMessageSender messageSender = new HttpComponentsMessageSender();
        // Set timeouts from configuration properties
        messageSender.setConnectionTimeout(Duration.ofMillis(connectionTimeoutMillis));
        messageSender.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        log.info("HttpComponentsMessageSender configured with Connection Timeout: {}ms, Read Timeout: {}ms",
                 connectionTimeoutMillis, readTimeoutMillis);
        return messageSender;
    }

    @Bean
    public WebServiceTemplate webServiceTemplate(Jaxb2Marshaller marshaller,
                                                 HttpComponentsMessageSender messageSender,
                                                 @Value("${nfse.service.url}") String defaultUri) {
        WebServiceTemplate template = new WebServiceTemplate();
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        template.setDefaultUri(defaultUri);
        template.setMessageSender(messageSender); // Use the configured HttpComponentsMessageSender
        log.info("WebServiceTemplate configured with default URI: {}", defaultUri);
        return template;
    }
}

