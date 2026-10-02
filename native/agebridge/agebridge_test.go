package agebridge

import (
 "io"
 "os"
 "path/filepath"
 "testing"
 "filippo.io/age"
)

func TestOfficialAgeRoundTrip(t *testing.T) {
 identity, err := age.GenerateX25519Identity()
 if err != nil { t.Fatal(err) }
 dir := t.TempDir()
 input, output := filepath.Join(dir,"input"), filepath.Join(dir,"output.age")
 if err := os.WriteFile(input, []byte("private payload"), 0600); err != nil { t.Fatal(err) }
 if err := EncryptFile(input, output, identity.Recipient().String()); err != nil { t.Fatal(err) }
 file, err := os.Open(output)
 if err != nil { t.Fatal(err) }
 defer file.Close()
 decrypted, err := age.Decrypt(file, identity)
 if err != nil { t.Fatal(err) }
 payload, err := io.ReadAll(decrypted)
 if err != nil || string(payload) != "private payload" { t.Fatalf("round trip failed: %v", err) }
 other, _ := age.GenerateX25519Identity()
 file.Seek(0,0)
 if _, err := age.Decrypt(file, other); err == nil { t.Fatal("wrong identity accepted") }
}
func TestInvalidRecipient(t *testing.T) {
 if ValidateRecipients("") == nil || ValidateRecipients("not-an-age-key") == nil { t.Fatal("invalid recipient accepted") }
}
