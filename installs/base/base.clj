(->> {:port 58615
      :channel-prefix "SCCP/"
      :outgoing-context "internal"
      :outgoint-timeout 45000
      :ami {:ip-address "192.168.18.30"
            :username   "accm"
            :password   "h2e9d49"}}
     (ref-set properties)
     dosync)