package net.grinder.util;

import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;

import static org.fest.assertions.Assertions.assertThat;

/**
 * Created by junoyoon on 15. 8. 28.
 */
public class NetworkUtilsTest {

	@Test
	public void testTryHttpConnection() throws Exception {
		assertThat(NetworkUtils.tryHttpConnection("http://54.251.14.87:10000", 2000,
				new Proxy(Proxy.Type.HTTP, new InetSocketAddress("10.251.51.68", 52412)))).isTrue();

	}
}