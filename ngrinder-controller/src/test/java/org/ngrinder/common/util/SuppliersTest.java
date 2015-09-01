package org.ngrinder.common.util;


import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;
import static org.ngrinder.common.util.Suppliers.memoizeWithExpiration;

/**
 * Supplier test. test invalidate logic.
 */
public class SuppliersTest {
	@Test
	public void testSupplier() {
		Suppliers.Supplier<Integer> value = memoizeWithExpiration(new Suppliers.Supplier<Integer>() {
			@Override
			public Integer get() {
				return (int) (Math.random() * 10000);
			}

			@Override
			public void invalidate() {
				// Do nothing.
			}
		}, 1, TimeUnit.SECONDS);
		Integer integer = value.get();
		assertThat(integer).isEqualTo(value.get());
		ThreadUtils.sleep(1002);
		Integer other = value.get();
		assertThat(integer).isNotEqualTo(other);
		value.invalidate();
		final Integer other1 = value.get();
		assertThat(other).isNotEqualTo(other1);
		ThreadUtils.sleep(300);
		assertThat(other1).isEqualTo(value.get());

	}

}