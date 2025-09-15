package org.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RegistruEvidentaDto {
    private String dataDeclaratie;
    private String nrMrn;
    private String identificare;
    private String numeExportator;
    private String buc;
    private String greutate;
    private String descriereaMarfurilor;
}
