package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum ExternalStatusReasonCode {

    AB_03("AB03"),
    AB_09("AB09"),
    AB_11("AB11"),
    AC_03("AC03"),
    AC_06("AC06"),
    AC_07("AC07"),
    AC_14("AC14"),
    AG_03("AG03"),
    AG_12("AG12"),
    AG_13("AG13"),
    AGNT("AGNT"),
    AM_01("AM01"),
    AM_02("AM02"),
    AM_04("AM04"),
    AM_09("AM09"),
    AM_12("AM12"),
    AM_18("AM18"),
    BE_01("BE01"),
    BE_05("BE05"),
    BE_15("BE15"),
    BE_17("BE17"),
    CH_11("CH11"),
    CH_16("CH16"),
    CN_01("CN01"),
    DS_04("DS04"),
    DS_0_G("DS0G"),
    DS_27("DS27"),
    DT_02("DT02"),
    DT_05("DT05"),
    DUPL("DUPL"),
    ED_05("ED05"),
    FF_07("FF07"),
    FF_08("FF08"),
    FRAD("FRAD"),
    MD_01("MD01"),
    RC_09("RC09"),
    RC_10("RC10"),
    RR_04("RR04"),
    SL_02("SL02"),
    UPAY("UPAY");

    @JsonValue
    private final String value;

    ExternalStatusReasonCode(String v) {
        value = v;
    }
}
