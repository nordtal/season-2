# spec

A settings group described as an annotated interface (`@ConfigSpec`), and the schema Steward draws
its forms from. `Specs` creates and inspects a spec's values, `SchemaWriter` builds its schema
tree, and `:settings` stores both in the database.

The proxy machinery is [Spec](https://github.com/Revxrsal/spec) (MIT), vendored into
`eu.nordtal.season.spec`. The repository's [NOTICE](../NOTICE) carries its licence; the
header of each vendored file says what changed.
