/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.app.computer.vision.common.segmentation;

import java.io.IOException;
import java.util.Collections;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.tensorflow.Tensor;
import org.tensorflow.types.UInt8;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.app.tensorflow.processor.DefaultOutputMessageBuilder;
import org.springframework.cloud.stream.app.tensorflow.processor.OutputMessageBuilder;
import org.springframework.cloud.stream.app.tensorflow.processor.TensorflowCommonProcessorConfiguration;
import org.springframework.cloud.stream.app.tensorflow.processor.TensorflowCommonProcessorProperties;
import org.springframework.cloud.stream.app.tensorflow.processor.TensorflowInputConverter;
import org.springframework.cloud.stream.app.tensorflow.processor.TensorflowOutputConverter;
import org.springframework.cloud.stream.messaging.Processor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.util.MimeTypeUtils;

/**
 *
 * @author Christian Tzolov
 */
@EnableBinding(Processor.class)
@EnableConfigurationProperties({ SemanticSegmentationProcessorProperties.class, TensorflowCommonProcessorProperties.class })
@Import(TensorflowCommonProcessorConfiguration.class)
abstract public class AbstractSemanticSegmentationProcessorConfiguration<T> {

	private static final Log logger = LogFactory.getLog(AbstractSemanticSegmentationProcessorConfiguration.class);

	private static final int BATCH_SIZE = 1;

	@Autowired
	private SemanticSegmentationProcessorProperties properties;

	@Autowired
	private TensorflowCommonProcessorProperties commonProperties;

	@Autowired
	private SemanticSegmentationService<T> segmentationService;

	@Bean
	public TensorflowInputConverter tensorflowInputConverter() {
		logger.info("Load ObjectDetectionTensorflowInputConverter");
		return (input, processorContext) -> {
			if (input instanceof byte[]) {
				T scaledImage = this.segmentationService.scaledImage((byte[]) input);
				Tensor<UInt8> inTensor = this.segmentationService.createInputTensor(scaledImage);
				return Collections.singletonMap(SemanticSegmentationService.INPUT_TENSOR_NAME, inTensor);
			}
			throw new IllegalArgumentException(String.format("Expected byte[] payload type, found: %s", input));
		};
	}

	@Bean
	public TensorflowOutputConverter<long[][]> tensorflowOutputConverter() {
		if (logger.isInfoEnabled()) {
			logger.info("Load Semantic Segmentation");
		}
		return (resultTensors, processorContext) -> {
			Tensor<?> outputTensor = resultTensors.get(SemanticSegmentationService.OUTPUT_TENSOR_NAME);
			int width = (int) outputTensor.shape()[1];
			int height = (int) outputTensor.shape()[2];
			long[][] maskPixels = outputTensor.copyTo(new long[BATCH_SIZE][width][height])[0];
			return maskPixels;
		};
	}

	@Bean
	public OutputMessageBuilder tensorflowOutputMessageBuilder() {
		return new DefaultOutputMessageBuilder(commonProperties) {
			@Override
			public MessageBuilder<?> createOutputMessageBuilder(Message<?> inputMessage, Object computedScore) {

				Message<?> outputMessage = inputMessage;

				int[][] maskPixels = segmentationService.toIntArray((long[][]) computedScore);

				if (properties.isMaskBlendingEnabled()) {
					try {
						int height = maskPixels.length;
						int width = maskPixels[0].length;
						byte[] inputImage = (byte[]) inputMessage.getPayload();

						T scaledImage = segmentationService.scaledImage(inputImage);

						T maskImage = segmentationService.createMaskImage(
								maskPixels, width, height, properties.getMaskTransparency());

						T blend = segmentationService.blendMask(maskImage, scaledImage);

						outputMessage = MessageBuilder.withPayload(toByteArray(blend))
								.setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_OCTET_STREAM).build();
					}
					catch (Exception e) {
						logger.error("Failed to create output message", e);
					}
				}

				return super.createOutputMessageBuilder(outputMessage, maskPixels);
			}
		};
	}

	@Bean
	abstract public SemanticSegmentationService<T> semanticSegmentationService();

	abstract protected byte[] toByteArray(T image) throws IOException;
}
