package io.notifyflow.api;

import java.io.*;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice
public class RequestSizeLimit extends RequestBodyAdviceAdapter {
  @Override
  public boolean supports(
      MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
    return true;
  }

  @Override
  public HttpInputMessage beforeBodyRead(
      HttpInputMessage input,
      MethodParameter parameter,
      Type type,
      Class<? extends HttpMessageConverter<?>> converter)
      throws IOException {
    byte[] bytes = input.getBody().readNBytes(65537);
    if (bytes.length > 65536)
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds 64 KiB");
    return new HttpInputMessage() {
      @Override
      public InputStream getBody() {
        return new ByteArrayInputStream(bytes);
      }

      @Override
      public HttpHeaders getHeaders() {
        return input.getHeaders();
      }
    };
  }
}
