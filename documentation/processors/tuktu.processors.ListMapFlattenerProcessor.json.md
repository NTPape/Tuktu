### tuktu.processors.ListMapFlattenerProcessor
Takes a list of maps and flattens it by reading out a specific key of that map. The resulting list will only contain the value of the key field.

  * **id** *(type: string)* `[Required]`

  * **result** *(type: string)* `[Required]`

  * **config** *(type: object)* `[Required]`

    * **list_field** *(type: string)* `[Required]`
    - The name of the field that contains the list of maps.

    * **map_field** *(type: string)* `[Required]`
    - The name of the field that needs to be extracted from the separate maps.

    * **ignore_empty** *(type: boolean)* `[Optional, default = true]`
    - If set to true, will only continue with non-empty values

    * **overwrite** *(type: boolean)* `[Optional, default = true]`
    - Whether or not to overwrite the original list_field.

