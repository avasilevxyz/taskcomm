package xyz.avasilev.taskcomm.communication.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;

/**
 * Разбор SOAP по умолчанию снимает конверт SOAP 1.1 и 1.2 и разбирает первый элемент {@code Body};
 * тело без конверта и DTD — неразбираемые (docs/adr/0135-communication-engine.md).
 */
class SoapBodyDecoderTest {

    private static final ResolvableType ORDER = ResolvableType.forClass(Order.class);

    private final SoapBodyDecoder decoder = new SoapBodyDecoder();

    @Test
    void unwrapsBodyOfSoap11AndSoap12() {
        assertThat(this.decoder.decode("""
                        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                          <s:Header><auth>x</auth></s:Header>
                          <s:Body><Order><number>1</number></Order></s:Body>
                        </s:Envelope>""", ORDER)).isEqualTo(new Order("1"));
        assertThat(this.decoder.decode(
                        "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\"><e:Body>"
                                + "<o:Order xmlns:o=\"urn:orders\"><number>2</number></o:Order></e:Body></e:Envelope>",
                        ORDER))
                .isEqualTo(new Order("2"));
    }

    @Test
    void rejectsBodyWithoutEnvelopeAndDoctype() {
        assertThatThrownBy(() -> this.decoder.decode("<Order><number>1</number></Order>", ORDER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не SOAP-конверт");
        assertThatThrownBy(() -> this.decoder.decode(
                        "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                                + "<Order><number>&e;</number></Order></s:Body></s:Envelope>",
                        ORDER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Заказ.
     *
     * @param number номер
     */
    record Order(String number) {}
}
