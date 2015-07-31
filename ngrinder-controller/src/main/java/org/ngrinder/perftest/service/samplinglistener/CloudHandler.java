package org.ngrinder.perftest.service.samplinglistener;

import java.util.Properties;

/**
 * Created by junoyoon on 15. 7. 27.
 */
public enum CloudHandler {
    AWS_EC2 {
        @Override
        public Properties process(Properties properties) {
            return properties;
        }
    },
    MESOS {
        @Override
        public Properties process(Properties properties) {
            return null;
        }
    };

    public abstract Properties process(Properties properties);


}
