package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// geoEntry — запись справочника: поле 1 — название, дальше содержимое.
func geoEntry(name string, payload []byte) []byte {
	var body bytes.Buffer
	body.WriteByte(0x0A)
	body.Write(binary.AppendUvarint(nil, uint64(len(name))))
	body.WriteString(name)
	body.Write(payload)

	out := []byte{0x0A}
	out = binary.AppendUvarint(out, uint64(body.Len()))
	return append(out, body.Bytes()...)
}

func withGeo(t *testing.T, site, ip []byte) {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "geosite.dat"), site, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "geoip.dat"), ip, 0o644); err != nil {
		t.Fatal(err)
	}
	old := assetsAt
	assetsAt = dir
	t.Cleanup(func() { assetsAt = old })
}

func TestGeoKnownReadsFiles(t *testing.T) {
	site := append(geoEntry("TLD-RU", bytes.Repeat([]byte{0x12}, 300)), geoEntry("private", nil)...)
	withGeo(t, site, geoEntry("RU", []byte{0x12, 0x00}))

	cases := map[string]bool{
		"geosite:tld-ru":           true,
		"geosite:private":          true,
		"geosite:tld-ru@ads":       true,
		"geosite:category-ads-all": false,
		"geoip:ru":                 true,
		"geoip:private":            false,
	}
	for ref, want := range cases {
		if got := geoKnown(ref); got != want {
			t.Errorf("geoKnown(%q) = %v, want %v", ref, got, want)
		}
	}
}

func TestRoutingGroupsDropUnknownCategories(t *testing.T) {
	withGeo(t, geoEntry("TLD-RU", nil), geoEntry("RU", nil))

	raw := []byte(`{"groups":[
		{"id":"ads","rules":[{"outboundTag":"block","domain":["geosite:category-ads-all"]}]},
		{"id":"ru","toggle":"ru_direct","rules":[{"outboundTag":"direct","domain":["geosite:tld-ru","geosite:nope"]}]},
		{"id":"later","toggle":"unknown_switch","default":false,"rules":[{"outboundTag":"block","port":"80"}]},
		{"id":"common","rules":[{"outboundTag":"block","network":"udp","port":"443"}]}
	]}`)
	var r routingAnswer
	if err := json.Unmarshal(raw, &r); err != nil {
		t.Fatal(err)
	}

	var ids []string
	var rules []map[string]any
	for _, g := range r.activeGroups() {
		ids = append(ids, g.ID)
		for _, rr := range g.Rules {
			if rule := decodeRule(rr); rule != nil {
				rules = append(rules, rule)
			}
		}
	}
	if len(ids) != 3 || ids[0] != "ads" || ids[2] != "common" {
		t.Fatalf("группы: %v", ids)
	}
	// Правило рекламы осталось без условий и выброшено целиком.
	if len(rules) != 2 {
		t.Fatalf("правил %d: %v", len(rules), rules)
	}
	if d := rules[0]["domain"].([]any); len(d) != 1 || d[0] != "geosite:tld-ru" {
		t.Errorf("domain: %v", d)
	}
	if dns := directDomains(&r); len(dns) != 1 || dns[0] != "geosite:tld-ru" {
		t.Errorf("directDomains: %v", dns)
	}
}
