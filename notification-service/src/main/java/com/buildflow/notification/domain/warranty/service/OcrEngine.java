package com.buildflow.notification.domain.warranty.service;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** 렌더링한 PDF 한 페이지에서 글자를 읽는다. 구현체는 시간 제한을 넘기면 IOException을 던진다. */
public interface OcrEngine {

    String recognize(BufferedImage page) throws IOException;
}
