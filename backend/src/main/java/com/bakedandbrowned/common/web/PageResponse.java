package com.bakedandbrowned.common.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** The contract's page shape ({@code ProductPage}, {@code AdminProductPage}, ...). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {

	public static <E, T> PageResponse<T> of(Page<E> page, Function<? super E, ? extends T> mapper) {
		return new PageResponse<>(page.getContent().stream().<T>map(mapper).toList(), page.getNumber(), page.getSize(),
				page.getTotalElements());
	}

}
