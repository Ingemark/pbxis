(->> {:port 58615
      :channel-prefix "SCCP/"
      :originate-context "internal"
      :originate-timeout-seconds 45
      :poll-timeout-seconds 30
      :ami {:ip-address "192.168.18.30"
            :username   "accm"
            :password   "h2e9d49"}}
     (ref-set properties)
     dosync)