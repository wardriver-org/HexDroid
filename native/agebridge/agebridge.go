// Package agebridge exposes official age file encryption to Android through gomobile.
package agebridge

import (
 "fmt"
 "io"
 "os"
 "strings"
 "filippo.io/age"
)

// ValidateRecipients accepts one or more native age1 public recipients, one per line.
func ValidateRecipients(text string) error {
 _, err := recipients(text)
 return err
}

func recipients(text string) ([]age.Recipient, error) {
 var result []age.Recipient
 for _, line := range strings.Fields(text) {
  recipient, err := age.ParseX25519Recipient(line)
  if err != nil { return nil, fmt.Errorf("invalid age1 recipient: %w", err) }
  result = append(result, recipient)
 }
 if len(result) == 0 { return nil, fmt.Errorf("add at least one age1 public recipient") }
 return result, nil
}

// EncryptFile streams a binary .age file. No keys or plaintext are returned to Java.
func EncryptFile(inputPath, outputPath, recipientText string) (err error) {
 keys, err := recipients(recipientText)
 if err != nil { return err }
 input, err := os.Open(inputPath)
 if err != nil { return err }
 defer input.Close()
 output, err := os.OpenFile(outputPath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
 if err != nil { return err }
 defer func() {
  closeErr := output.Close()
  if err == nil { err = closeErr }
  if err != nil { os.Remove(outputPath) }
 }()
 encrypted, err := age.Encrypt(output, keys...)
 if err != nil { return err }
 if _, err = io.Copy(encrypted, input); err != nil { return err }
 return encrypted.Close()
}
