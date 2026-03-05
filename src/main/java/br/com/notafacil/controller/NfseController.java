package br.com.notafacil.controller;

import br.com.notafacil.dto.CabecalhoDto;
import br.com.notafacil.dto.EmitirNotaRequest;
import br.com.notafacil.dto.EnviarLoteRpsEnvioDto;
import br.com.notafacil.schemas.ConsultarLoteRpsResposta;
import br.com.notafacil.schemas.ConsultarSituacaoLoteRpsResposta;
import br.com.notafacil.schemas.EnviarLoteRpsResposta;
import br.com.notafacil.service.NfseService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/nfse")
@Validated
public class NfseController {

    private static final Logger log = LoggerFactory.getLogger(NfseController.class);
    private final NfseService service;

    // Inject default header versions from application.yml
    @Value("${nfse.cabecalho.versao:3}")
    private String cabecalhoVersao;

    @Value("${nfse.cabecalho.versao-dados:3}")
    private String cabecalhoVersaoDados;

    public NfseController(NfseService service) {
        this.service = service;
    }

    @PostMapping("/recepcionar-lote-rps")
    public ResponseEntity<EnviarLoteRpsResposta> recepcionarLoteRps(
            @RequestBody @Valid EnviarLoteRpsEnvioDto dto) {
        log.info("Recebida requisição para recepcionar lote RPS. Quantidade de RPS: {}", dto.loteRps().quantidadeRps());
        CabecalhoDto cabecalhoDto = new CabecalhoDto(cabecalhoVersao, cabecalhoVersaoDados);
        EnviarLoteRpsResposta resposta = service.recepcionarLote(cabecalhoDto, dto);
        log.info("Lote RPS recepcionado com sucesso. Protocolo: {}", resposta.getProtocolo());
        return ResponseEntity.ok(resposta);
    }

    @GetMapping("/consulta-situacao-lote-rps/{protocolo}")
    public ResponseEntity<ConsultarSituacaoLoteRpsResposta> consultarSituacaoLoteRps(
            @PathVariable("protocolo") String protocolo) {
        log.info("Recebida requisição para consultar situação do lote RPS. Protocolo: {}", protocolo);
        CabecalhoDto cabecalhoDto = new CabecalhoDto(cabecalhoVersao, cabecalhoVersaoDados);
        ConsultarSituacaoLoteRpsResposta resposta = service.consultarSituacaoLoteRps(cabecalhoDto, protocolo);
        log.info("Consulta de situação do lote RPS realizada. Situação: {}", resposta.getSituacao());
        return ResponseEntity.ok(resposta);
    }

    @GetMapping("/consulta-lote-rps/{protocolo}")
    public ResponseEntity<ConsultarLoteRpsResposta> consultarLoteRps(
            @PathVariable("protocolo") String protocolo) {
        log.info("Recebida requisição para consultar lote RPS. Protocolo: {}", protocolo);
        CabecalhoDto cabecalhoDto = new CabecalhoDto(cabecalhoVersao, cabecalhoVersaoDados);
        ConsultarLoteRpsResposta resposta = service.consultarLoteRps(cabecalhoDto, protocolo);
        // Log relevant info from response, e.g., number of NFS-e found
        int countNfse = (resposta.getListaNfse() != null && resposta.getListaNfse().getCompNfse() != null)
                      ? resposta.getListaNfse().getCompNfse().size() : 0;
        log.info("Consulta de lote RPS realizada. Protocolo: {}. Quantidade de NFSe encontradas: {}", protocolo, countNfse);
        return ResponseEntity.ok(resposta);
    }

    @PostMapping("/emitir-rps")
    public ResponseEntity<Void> emitirRpsEmLote(
            @RequestBody @Valid EmitirNotaRequest request) {
        log.info("Recebida requisição para emitir RPS em lote. Quantidade de RPS: {}", request.listaRps().size());
        // Using configured header versions
        CabecalhoDto cabecalhoDto = new CabecalhoDto(cabecalhoVersao, cabecalhoVersaoDados);
        service.emitirRpsEmLotes(cabecalhoDto, request.listaRps());
        log.info("Requisição para emissão de RPS em lote aceita para processamento.");
        // Consider returning 202 Accepted as the processing is likely asynchronous
        return ResponseEntity.accepted().build();
    }
}

