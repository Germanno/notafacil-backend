package br.com.notafacil.service;

import br.com.notafacil.dto.*;
import br.com.notafacil.exception.NfseClientException;
import br.com.notafacil.exception.NfseProcessingException;
import br.com.notafacil.mapping.NfseMapper;
import br.com.notafacil.schemas.*;
import br.com.notafacil.wsdl.ServiceGinfes;
import br.com.notafacil.wsdl.ServiceGinfesImplServiceService;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.soap.client.SoapFaultClientException;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

@Service
public class NfseService {

    private static final Logger log = LoggerFactory.getLogger(NfseService.class);

    private final ServiceGinfes port;
    private final NfseMapper mapper;
    private final JAXBContext jaxbCtx;
    private final AzureVaultXmlSigningService xmlSigner;

    // Injected Prestador details from application.yml
    @Value("${nfse.prestador.cnpj}")
    private String prestadorCnpj;

    @Value("${nfse.prestador.inscricao-municipal}")
    private String prestadorInscricaoMunicipal;

    // WARNING: In-memory counters are NOT suitable for production or multi-instance environments.
    // Use a database sequence or a distributed locking mechanism for generating unique IDs.
    private final AtomicLong loteCounter = new AtomicLong(1); // TODO: Replace with persistent sequence
    private final AtomicLong rpsCounter = new AtomicLong(1);  // TODO: Replace with persistent sequence

    private static final int MAX_RPS_PER_LOTE = 50;

    public NfseService(NfseMapper mapper, AzureVaultXmlSigningService xmlSigner,
                       @Value("${nfse.service.url}") String serviceUrl) {
        this.mapper = mapper;
        this.xmlSigner = xmlSigner;

        try {
            log.info("Initializing JAXBContext for NFSe schemas...");
            // Initialize JAXBContext with all relevant schema classes
            this.jaxbCtx = JAXBContext.newInstance(
                    Cabecalho.class,
                    EnviarLoteRpsEnvio.class, EnviarLoteRpsResposta.class,
                    ConsultarLoteRpsEnvio.class, ConsultarLoteRpsResposta.class,
                    ConsultarSituacaoLoteRpsEnvio.class, ConsultarSituacaoLoteRpsResposta.class,
                    ConsultarNfseEnvio.class, ConsultarNfseResposta.class, // Add others if needed
                    ConsultarNfseRpsEnvio.class, ConsultarNfseRpsResposta.class // Add others if needed
            );
            log.info("JAXBContext initialized successfully.");

            log.info("Creating JAX-WS client stub for NFSe service at URL: {}", serviceUrl);
            // Consider injecting the WebServiceTemplate directly instead of the raw port if using Spring WS template features
            ServiceGinfesImplServiceService svc = new ServiceGinfesImplServiceService();
            // Potentially configure the service endpoint address here if needed
            this.port = svc.getServiceGinfes();
            log.info("JAX-WS client stub created successfully.");

        } catch (JAXBException e) {
            log.error("Failed to initialize JAXBContext", e);
            throw new IllegalStateException("Failed to initialize JAXBContext for NFSe schemas", e);
        } catch (Exception e) {
            log.error("Failed to initialize NfseService dependencies", e);
            throw new IllegalStateException("Failed to initialize NfseService dependencies", e);
        }
    }

    public EnviarLoteRpsResposta recepcionarLote(CabecalhoDto cabecalhoDto, EnviarLoteRpsEnvioDto enviarLoteRpsEnvioDto) {
        String loteId = "lote-" + UUID.randomUUID();
        log.info("Starting recepcionarLote process for Lote ID: {}", loteId);

        try {
            // 1. Marshal Header
            String cabecalhoXml = marshalToXmlFragment(createCabecalho(cabecalhoDto), "Header");
            log.debug("Marshalled Header XML: {}", cabecalhoXml);

            // 2. Marshal Body (EnviarLoteRpsEnvio)
            EnviarLoteRpsEnvio envioJaxb = mapper.toSchema(enviarLoteRpsEnvioDto, loteId);
            String unsignedBodyXml = marshalToXmlFragment(envioJaxb, "Body");
            log.debug("Marshalled Unsigned Body XML: {}", unsignedBodyXml);

            // 3. Sign Body XML
            log.info("Signing XML for Lote ID: {}", loteId);
            String signedBodyXml = xmlSigner.signXml(unsignedBodyXml, loteId); // Assuming signXml needs the ID for referencing
            log.debug("Signed Body XML: {}", signedBodyXml);

            // 4. Call SOAP Service
            log.info("Calling Ginfes service: recepcionarLoteRpsV3 for Lote ID: {}", loteId);
            String respostaXml = port.recepcionarLoteRpsV3(cabecalhoXml, signedBodyXml);
            log.debug("Received SOAP Response XML: {}", respostaXml);

            // 5. Unmarshal Response
            EnviarLoteRpsResposta resposta = unmarshalFromXml(respostaXml, EnviarLoteRpsResposta.class, "Response");
            log.info("Successfully processed recepcionarLote for Lote ID: {}. Protocolo: {}", loteId, resposta.getProtocolo());
            return resposta;

        } catch (JAXBException e) {
            log.error("JAXB marshalling/unmarshalling error during recepcionarLote for Lote ID: {}", loteId, e);
            throw new NfseProcessingException("XML processing error for Lote ID: " + loteId, e);
        } catch (SoapFaultClientException e) {
            log.error("SOAP Fault received during recepcionarLote for Lote ID: {}. Fault: {}", loteId, e.getSoapFault().getFaultStringOrReason(), e);
            throw new NfseClientException("SOAP Fault from Ginfes service: " + e.getFaultStringOrReason(), e);
        } catch (WebServiceIOException | WebServiceTransportException e) {
            log.error("Communication error during recepcionarLote for Lote ID: {}", loteId, e);
            throw new NfseClientException("Communication error with Ginfes service", e);
        } catch (Exception e) {
            log.error("Unexpected error during recepcionarLote for Lote ID: {}", loteId, e);
            throw new NfseProcessingException("Unexpected error processing Lote ID: " + loteId, e);
        }
    }

    public ConsultarSituacaoLoteRpsResposta consultarSituacaoLoteRps(CabecalhoDto cabecalhoDto, String protocolo) {
        log.info("Starting consultarSituacaoLoteRps process for Protocolo: {}", protocolo);
        try {
            // 1. Marshal Header
            String cabecalhoXml = marshalToXmlFragment(createCabecalho(cabecalhoDto), "Header");
            log.debug("Marshalled Header XML: {}", cabecalhoXml);

            // 2. Create Request Body
            ConsultarSituacaoLoteRpsEnvio envio = new ConsultarSituacaoLoteRpsEnvio();
            TcIdentificacaoPrestador prestadorId = createPrestadorIdentificacao();
            envio.setPrestador(prestadorId);
            envio.setProtocolo(protocolo);
            log.debug("Created ConsultarSituacaoLoteRpsEnvio object for Protocolo: {}", protocolo);

            // 3. Marshal Body
            String unsignedBodyXml = marshalToXmlFragment(envio, "Body");
            log.debug("Marshalled Unsigned Body XML: {}", unsignedBodyXml);

            // 4. Sign Body XML (Optional - Check if required by the service specification)
            log.info("Signing XML for ConsultarSituacaoLoteRps Protocolo: {}", protocolo);
            // Assuming the signature ID should relate to the request, using protocol might be suitable if unique
            String signedBodyXml = xmlSigner.signXml(unsignedBodyXml); // Check if ID is needed
            log.debug("Signed Body XML: {}", signedBodyXml);

            // 5. Call SOAP Service
            log.info("Calling Ginfes service: consultarSituacaoLoteRpsV3 for Protocolo: {}", protocolo);
            String respostaXml = port.consultarSituacaoLoteRpsV3(cabecalhoXml, signedBodyXml);
            log.debug("Received SOAP Response XML: {}", respostaXml);

            // 6. Unmarshal Response
            ConsultarSituacaoLoteRpsResposta resposta = unmarshalFromXml(respostaXml, ConsultarSituacaoLoteRpsResposta.class, "Response");
            log.info("Successfully processed consultarSituacaoLoteRps for Protocolo: {}. Situacao: {}", protocolo, resposta.getSituacao());
            return resposta;

        } catch (JAXBException e) {
            log.error("JAXB marshalling/unmarshalling error during consultarSituacaoLoteRps for Protocolo: {}", protocolo, e);
            throw new NfseProcessingException("XML processing error for Protocolo: " + protocolo, e);
        } catch (SoapFaultClientException e) {
            log.error("SOAP Fault received during consultarSituacaoLoteRps for Protocolo: {}. Fault: {}", protocolo, e.getSoapFault().getFaultStringOrReason(), e);
            throw new NfseClientException("SOAP Fault from Ginfes service: " + e.getFaultStringOrReason(), e);
        } catch (WebServiceIOException | WebServiceTransportException e) {
            log.error("Communication error during consultarSituacaoLoteRps for Protocolo: {}", protocolo, e);
            throw new NfseClientException("Communication error with Ginfes service", e);
        } catch (Exception e) {
            log.error("Unexpected error during consultarSituacaoLoteRps for Protocolo: {}", protocolo, e);
            throw new NfseProcessingException("Unexpected error processing Protocolo: " + protocolo, e);
        }
    }

    public ConsultarLoteRpsResposta consultarLoteRps(CabecalhoDto cabecalhoDto, String protocolo) {
        log.info("Starting consultarLoteRps process for Protocolo: {}", protocolo);
        try {
            // 1. Marshal Header
            String cabecalhoXml = marshalToXmlFragment(createCabecalho(cabecalhoDto), "Header");
            log.debug("Marshalled Header XML: {}", cabecalhoXml);

            // 2. Create Request Body
            ConsultarLoteRpsEnvio envio = new ConsultarLoteRpsEnvio();
            TcIdentificacaoPrestador prestadorId = createPrestadorIdentificacao();
            envio.setPrestador(prestadorId);
            envio.setProtocolo(protocolo);
            log.debug("Created ConsultarLoteRpsEnvio object for Protocolo: {}", protocolo);

            // 3. Marshal Body
            String unsignedBodyXml = marshalToXmlFragment(envio, "Body");
            log.debug("Marshalled Unsigned Body XML: {}", unsignedBodyXml);

            // 4. Sign Body XML (Optional - Check if required)
            log.info("Signing XML for ConsultarLoteRps Protocolo: {}", protocolo);
            String signedBodyXml = xmlSigner.signXml(unsignedBodyXml); // Check if ID is needed
            log.debug("Signed Body XML: {}", signedBodyXml);

            // 5. Call SOAP Service
            log.info("Calling Ginfes service: consultarLoteRpsV3 for Protocolo: {}", protocolo);
            String respostaXml = port.consultarLoteRpsV3(cabecalhoXml, signedBodyXml);
            log.debug("Received SOAP Response XML: {}", respostaXml);

            // 6. Unmarshal Response
            ConsultarLoteRpsResposta resposta = unmarshalFromXml(respostaXml, ConsultarLoteRpsResposta.class, "Response");
            int countNfse = (resposta.getListaNfse() != null && resposta.getListaNfse().getCompNfse() != null)
                    ? resposta.getListaNfse().getCompNfse().size() : 0;
            log.info("Successfully processed consultarLoteRps for Protocolo: {}. Found {} NFSe.", protocolo, countNfse);
            return resposta;

        } catch (JAXBException e) {
            log.error("JAXB marshalling/unmarshalling error during consultarLoteRps for Protocolo: {}", protocolo, e);
            throw new NfseProcessingException("XML processing error for Protocolo: " + protocolo, e);
        } catch (SoapFaultClientException e) {
            log.error("SOAP Fault received during consultarLoteRps for Protocolo: {}. Fault: {}", protocolo, e.getSoapFault().getFaultStringOrReason(), e);
            throw new NfseClientException("SOAP Fault from Ginfes service: " + e.getFaultStringOrReason(), e);
        } catch (WebServiceIOException | WebServiceTransportException e) {
            log.error("Communication error during consultarLoteRps for Protocolo: {}", protocolo, e);
            throw new NfseClientException("Communication error with Ginfes service", e);
        } catch (Exception e) {
            log.error("Unexpected error during consultarLoteRps for Protocolo: {}", protocolo, e);
            throw new NfseProcessingException("Unexpected error processing Protocolo: " + protocolo, e);
        }
    }

    /**
     * Processes a list of RPS requests, groups them into batches, and sends them to the recepcionarLote endpoint.
     *
     * @param cabecalhoDto Header information.
     * @param listaRps     List of RPS requests to process.
     */
    public void emitirRpsEmLotes(CabecalhoDto cabecalhoDto, List<InfRpsRequestDto> listaRps) {
        if (listaRps == null || listaRps.isEmpty()) {
            log.warn("emitirRpsEmLotes called with an empty or null list of RPS. No action taken.");
            return;
        }
        log.info("Starting emitirRpsEmLotes process for {} RPS requests.", listaRps.size());

        // 1. Group RPS requests into batches
        List<List<InfRpsRequestDto>> batches = groupIntoBatches(listaRps, MAX_RPS_PER_LOTE);
        log.info("Divided {} RPS requests into {} batches of max size {}.", listaRps.size(), batches.size(), MAX_RPS_PER_LOTE);

        // 2. Process each batch
        // Consider parallel processing for performance, but handle potential complexities (transactions, error handling)
        IntStream.range(0, batches.size()).forEach(index -> {
            List<InfRpsRequestDto> batch = batches.get(index);
            int batchNumber = index + 1;
            log.info("Processing batch {} of {} ({} RPS).", batchNumber, batches.size(), batch.size());
            try {
                // 2.1 Mount the Lote DTO for the current batch
                EnviarLoteRpsEnvioDto loteDto = montarLoteParaEnvio(batch);
                // 2.2 Send the batch
                recepcionarLote(cabecalhoDto, loteDto);
                log.info("Successfully sent batch {} of {}.", batchNumber, batches.size());
            } catch (NfseClientException | NfseProcessingException e) {
                // Log specific errors from recepcionarLote and continue with the next batch (or implement retry/failure strategy)
                log.error("Failed to process batch {} of {}: {}", batchNumber, batches.size(), e.getMessage(), e);
                // TODO: Implement error handling strategy (e.g., collect failures, notify, retry)
            } catch (Exception e) {
                // Catch unexpected errors for the batch
                log.error("Unexpected error processing batch {} of {}.", batchNumber, batches.size(), e);
                // TODO: Implement error handling strategy
            }
        });

        log.info("Finished emitirRpsEmLotes process.");
    }

    // --- Helper Methods ---

    private Cabecalho createCabecalho(CabecalhoDto dto) {
        Cabecalho cabecalho = new Cabecalho();
        cabecalho.setVersao(dto.versao());
        cabecalho.setVersaoDados(dto.versaoDados());
        return cabecalho;
    }

    private TcIdentificacaoPrestador createPrestadorIdentificacao() {
        TcIdentificacaoPrestador tp = new TcIdentificacaoPrestador();
        tp.setCnpj(this.prestadorCnpj); // Use injected value
        tp.setInscricaoMunicipal(this.prestadorInscricaoMunicipal); // Use injected value
        return tp;
    }

    private <T> String marshalToXmlFragment(T jaxbObject, String objectName) throws JAXBException {
        try {
            StringWriter sw = new StringWriter();
            Marshaller marshaller = jaxbCtx.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE); // Omit XML declaration
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE); // No formatting for production
            marshaller.marshal(jaxbObject, sw);
            log.debug("Successfully marshalled {} object.", objectName);
            return sw.toString();
        } catch (JAXBException e) {
            log.error("Failed to marshal {} object to XML fragment.", objectName, e);
            throw e;
        }
    }

    private <T> T unmarshalFromXml(String xml, Class<T> clazz, String objectName) throws JAXBException {
        try {
            Unmarshaller unmarshaller = jaxbCtx.createUnmarshaller();
            StringReader reader = new StringReader(xml);
            T result = (T) unmarshaller.unmarshal(reader);
            log.debug("Successfully unmarshalled XML to {} object.", objectName);
            return result;
        } catch (JAXBException e) {
            log.error("Failed to unmarshal XML to {} object.", objectName, e);
            throw e;
        }
    }

    /**
     * Creates the EnviarLoteRpsEnvioDto structure for a list of RPS requests.
     * This method assumes all RPS in the list belong to the same Prestador.
     */
    private EnviarLoteRpsEnvioDto montarLoteParaEnvio(List<InfRpsRequestDto> listaRequest) {
        if (listaRequest == null || listaRequest.isEmpty()) {
            throw new IllegalArgumentException("Cannot mount Lote for an empty or null RPS list.");
        }

        List<RpsDto> listaRpsDto = new ArrayList<>();
        // Assuming the prestador is the same for all RPS in the batch, get it from the first one.
        // Validation should ensure this consistency earlier if necessary.
        String cnpjPrestadorBatch = listaRequest.get(0).prestador().cnpj();
        String inscricaoPrestadorBatch = listaRequest.get(0).prestador().inscricaoMunicipal();

        for (InfRpsRequestDto req : listaRequest) {
            // TODO: Replace AtomicLong with a reliable sequence generator (DB sequence recommended)
            String numeroSequencialRps = String.valueOf(rpsCounter.getAndIncrement());
            String serie = String.valueOf(LocalDate.now().getYear()); // Consider making series configurable or based on logic
            Integer tipo = 2; // Assuming type 2 (RPS-Misto), make configurable if needed
            LocalDateTime dataEmissao = LocalDateTime.now(); // Use consistent time source

            // Map request DTO to InfRpsDto using MapStruct (assuming mapper handles nested objects)
            InfRpsDto infRpsDto = mapper.toInfRpsDto(req, numeroSequencialRps, serie, tipo, dataEmissao);
            listaRpsDto.add(new RpsDto(infRpsDto));
        }

        // TODO: Replace AtomicLong with a reliable sequence generator (DB sequence recommended)
        long idLote = loteCounter.getAndIncrement();
        // Ensure consistent formatting for NumeroLote if required by the schema
        String numeroLoteString = String.valueOf(idLote); // Format if needed: String.format("%010d", idLote);
        Integer quantidadeRps = listaRpsDto.size();

        LoteRpsDto loteDto = new LoteRpsDto(
                idLote, // Check if this ID needs specific format or just uniqueness
                numeroLoteString,
                cnpjPrestadorBatch,
                inscricaoPrestadorBatch,
                quantidadeRps,
                listaRpsDto
        );

        log.debug("Mounted LoteRpsDto with NumeroLote: {}, QuantidadeRps: {}", numeroLoteString, quantidadeRps);
        return new EnviarLoteRpsEnvioDto(loteDto);
    }

    /**
     * Groups a list into sublists of a specified maximum size.
     */
    private <T> List<List<T>> groupIntoBatches(List<T> list, int batchSize) {
        if (list == null || list.isEmpty() || batchSize <= 0) {
            return new ArrayList<>();
        }
        List<List<T>> batches = new ArrayList<>();
        int totalSize = list.size();
        for (int i = 0; i < totalSize; i += batchSize) {
            batches.add(new ArrayList<>(list.subList(i, Math.min(totalSize, i + batchSize))));
        }
        return batches;
    }
}

