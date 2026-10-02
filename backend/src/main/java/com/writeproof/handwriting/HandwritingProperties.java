package com.writeproof.handwriting;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** @param threshold minimum similarity score (0..1) for a verification to match */
@Validated
@ConfigurationProperties("writeproof.handwriting")
public record HandwritingProperties(@DecimalMin("0") @DecimalMax("1") double threshold) {}
