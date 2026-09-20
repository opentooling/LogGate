package com.opentooling.loggate.web;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Root configuration for the web slice tests in this package.
 *
 * <p>Without component scanning, the application class wires the whole object
 * graph with {@code @Import}, and a slice that found it would drag in the
 * database and Loki configuration it has no use for. A slice test searches
 * upwards from its own package for a {@code @SpringBootConfiguration}, so this
 * one is found first and each test declares exactly what it needs.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class WebSliceConfiguration {}
